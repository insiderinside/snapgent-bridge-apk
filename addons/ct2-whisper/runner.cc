#include <ctranslate2/models/whisper.h>
#include <ctranslate2/storage_view.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include "mel.h"

namespace {

using ctranslate2::Shape;
using ctranslate2::StorageView;

constexpr double kFrameSeconds = 0.02;
constexpr std::size_t kVadFrame = 320;  // 20 ms at 16 kHz

double now_s() {
  return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch())
      .count();
}

struct Vocab {
  std::vector<std::string> tokens;
  std::map<std::string, std::size_t> index;

  bool load(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f)
      return false;
    std::string line;
    while (std::getline(f, line)) {
      if (!line.empty() && line.back() == '\r')
        line.pop_back();
      index.emplace(line, tokens.size());
      tokens.push_back(line);
    }
    return !tokens.empty();
  }

  std::size_t id(const std::string& t) const {
    auto it = index.find(t);
    return it == index.end() ? (std::size_t)-1 : it->second;
  }
};

// GPT-2 byte-level BPE decoder table: unicode code point -> original byte.
struct ByteDecoder {
  std::vector<int> map;  // 0..0x2FF
  ByteDecoder() {
    map.assign(0x300, -1);
    std::vector<int> bs;
    for (int b = (int)'!'; b <= (int)'~'; ++b) bs.push_back(b);
    for (int b = 0xA1; b <= 0xAC; ++b) bs.push_back(b);
    for (int b = 0xAE; b <= 0xFF; ++b) bs.push_back(b);
    std::vector<int> cs = bs;
    int n = 0;
    for (int b = 0; b < 256; ++b) {
      if (std::find(bs.begin(), bs.end(), b) == bs.end()) {
        bs.push_back(b);
        cs.push_back(256 + n);
        ++n;
      }
    }
    for (std::size_t i = 0; i < bs.size(); ++i)
      if (cs[i] < 0x300)
        map[(std::size_t)cs[i]] = bs[i];
  }
};
const ByteDecoder& byte_decoder() {
  static ByteDecoder d;
  return d;
}

// Decodes one UTF-8 code point at position i; returns length consumed.
std::size_t decode_cp(const std::string& s, std::size_t i, unsigned int& cp) {
  const unsigned char c = (unsigned char)s[i];
  if (c < 0x80) {
    cp = c;
    return 1;
  }
  if ((c & 0xE0) == 0xC0 && i + 1 < s.size()) {
    cp = ((unsigned int)(c & 0x1F) << 6) | ((unsigned int)s[i + 1] & 0x3F);
    return 2;
  }
  if ((c & 0xF0) == 0xE0 && i + 2 < s.size()) {
    cp = ((unsigned int)(c & 0x0F) << 12) | (((unsigned int)s[i + 1] & 0x3F) << 6) |
         ((unsigned int)s[i + 2] & 0x3F);
    return 3;
  }
  if ((c & 0xF8) == 0xF0 && i + 3 < s.size()) {
    cp = ((unsigned int)(c & 0x07) << 18) | (((unsigned int)s[i + 1] & 0x3F) << 12) |
         (((unsigned int)s[i + 2] & 0x3F) << 6) | ((unsigned int)s[i + 3] & 0x3F);
    return 4;
  }
  cp = c;
  return 1;
}

// Decodes GPT-2 byte-level token text into UTF-8.
std::string detokenize(const std::string& tok) {
  const ByteDecoder& dec = byte_decoder();
  std::vector<unsigned char> bytes;
  bytes.reserve(tok.size());
  for (std::size_t i = 0; i < tok.size();) {
    unsigned int cp = 0;
    i += decode_cp(tok, i, cp);
    if (cp < dec.map.size() && dec.map[cp] >= 0)
      bytes.push_back((unsigned char)dec.map[cp]);
    else
      bytes.push_back((unsigned char)(cp & 0xFF));
  }
  return std::string(bytes.begin(), bytes.end());
}

bool is_special(const std::string& s) {
  return s.size() >= 4 && s.compare(0, 2, "<|") == 0 && s.compare(s.size() - 2, 2, "|>") == 0;
}

bool timestamp_seconds(const std::string& s, double& seconds) {
  if (!is_special(s))
    return false;
  const std::string body = s.substr(2, s.size() - 4);
  if (body.empty())
    return false;
  for (char c : body)
    if (!((c >= '0' && c <= '9') || c == '.'))
      return false;
  seconds = std::atof(body.c_str());
  return true;
}

std::string json_escape(const std::string& s) {
  std::string out;
  out.reserve(s.size() + 8);
  for (unsigned char c : s) {
    switch (c) {
      case '"': out += "\\\""; break;
      case '\\': out += "\\\\"; break;
      case '\n': out += "\\n"; break;
      case '\r': out += "\\r"; break;
      case '\t': out += "\\t"; break;
      default:
        if (c < 0x20) {
          char buf[8];
          std::snprintf(buf, sizeof(buf), "\\u%04x", c);
          out += buf;
        } else {
          out += (char)c;
        }
    }
  }
  return out;
}

struct Word {
  std::string text;
  double start = 0;
  double end = 0;
};

struct Segment {
  int chunk = 0;
  double start = 0;
  double end = 0;
  std::string text;
  std::vector<std::size_t> ids;
  std::vector<std::string> tokens;
  std::vector<Word> words;
};

// ---------------------------------------------------------------- voice activity

/**
 * Energy VAD used to skip windows that hold no speech.
 *
 * Whisper always pays one encoder pass per 30 s window and one decoder pass for
 * the batch, so a ten-minute recording with long pauses costs more than the
 * speech it contains. The detector is deliberately simple: 20 ms frames, a noise
 * floor taken from the quietest decile, a threshold 8 dB above it (never below
 * -55 dBFS so a very quiet recording is not classified as silence), then gaps up
 * to 400 ms are closed so a word is never cut in half. A window is kept when it
 * holds at least `min_speech_s` of speech.
 */
struct VadResult {
  std::vector<int> chunks;  // keep, in ascending order
  std::vector<double> ratio;
  bool enabled = false;
  double speech_s = 0;
  double skipped_s = 0;
};

VadResult compute_vad(const std::vector<float>& samples, bool enabled, double min_speech_s,
                      std::size_t chunk_samples) {
  VadResult result;
  result.enabled = enabled;
  const std::size_t frames = samples.size() / kVadFrame;
  const int total_chunks =
      (int)std::max<std::size_t>(1, (samples.size() + chunk_samples - 1) / chunk_samples);
  result.ratio.assign((std::size_t)total_chunks, 1.0);
  if (!enabled || frames == 0) {
    for (int c = 0; c < total_chunks; ++c)
      result.chunks.push_back(c);
    result.speech_s = (double)samples.size() / asr::kSampleRate;
    return result;
  }

  std::vector<double> db(frames, -120.0);
  std::vector<double> sorted;
  sorted.reserve(frames);
  for (std::size_t i = 0; i < frames; ++i) {
    double sum = 0;
    const float* frame = samples.data() + i * kVadFrame;
    for (std::size_t j = 0; j < kVadFrame; ++j) {
      const double v = frame[j];
      sum += v * v;
    }
    const double rms = std::sqrt(sum / (double)kVadFrame);
    db[i] = 20.0 * std::log10(std::max(rms, 1e-9));
    sorted.push_back(db[i]);
  }
  std::sort(sorted.begin(), sorted.end());
  const double floor_db = sorted[(std::size_t)((double)(sorted.size() - 1) * 0.10)];
  const double threshold = std::max(floor_db + 8.0, -55.0);

  std::vector<bool> speech(frames, false);
  for (std::size_t i = 0; i < frames; ++i)
    speech[i] = db[i] > threshold;

  // Close gaps up to 400 ms so short consonants inside a pause do not end a phrase.
  const std::size_t max_gap = 20;
  std::size_t last = (std::size_t)-1;
  for (std::size_t i = 0; i < frames; ++i) {
    if (!speech[i])
      continue;
    if (last != (std::size_t)-1 && i - last <= max_gap)
      for (std::size_t x = last + 1; x < i; ++x)
        speech[x] = true;
    last = i;
  }

  for (int c = 0; c < total_chunks; ++c) {
    const std::size_t begin = (std::size_t)c * chunk_samples / kVadFrame;
    const std::size_t end =
        std::min(frames, ((std::size_t)(c + 1) * chunk_samples + kVadFrame - 1) / kVadFrame);
    std::size_t voiced = 0;
    for (std::size_t i = begin; i < end; ++i)
      if (speech[i])
        ++voiced;
    const double seconds = (double)voiced * (double)kVadFrame / asr::kSampleRate;
    const double span = (double)(end - begin) * (double)kVadFrame / asr::kSampleRate;
    result.ratio[(std::size_t)c] = span > 0 ? seconds / span : 0.0;
    result.speech_s += seconds;
    if (seconds >= min_speech_s)
      result.chunks.push_back(c);
    else
      result.skipped_s += span;
  }
  return result;
}

// ---------------------------------------------------------------- transcription engine

struct TranscribeOptions {
  std::string language = "auto";
  std::size_t beam = 5;
  bool vad = true;
  double min_speech_s = 0.25;
};

struct TranscribeOutput {
  std::string language;
  double duration = 0;
  int chunks_total = 0;
  int chunks_used = 0;
  bool vad_enabled = false;
  double speech_s = 0;
  double skipped_s = 0;
  double t_load = 0;
  double t_mel = 0;
  double t_gen = 0;
  double t_align = 0;
  std::vector<Segment> segments;
};

class Engine {
 public:
  bool load(const std::string& model_dir, const std::string& filters_path, int threads,
            ctranslate2::ComputeType compute_type, std::string& err) {
    if (!asr::load_filterbank(filters_path, filters_, err))
      return false;
    if (!vocab_.load(model_dir + "/vocabulary.txt")) {
      err = "cannot read vocabulary.txt under " + model_dir;
      return false;
    }
    sot_ = vocab_.id("<|startoftranscript|>");
    transcribe_ = vocab_.id("<|transcribe|>");
    if (sot_ == (std::size_t)-1 || transcribe_ == (std::size_t)-1) {
      err = "vocabulary is missing whisper special tokens";
      return false;
    }
    ctranslate2::ReplicaPoolConfig pool_config;
    pool_config.num_threads_per_replica = (std::size_t)std::max(1, threads);
    const double t0 = now_s();
    model_ = std::make_unique<ctranslate2::models::Whisper>(model_dir, ctranslate2::Device::CPU,
                                                           compute_type, std::vector<int>{0},
                                                           false, pool_config);
    load_seconds_ = now_s() - t0;
    return true;
  }

  double load_seconds() const { return load_seconds_; }

  TranscribeOutput run(const std::vector<float>& samples, const TranscribeOptions& options) {
    std::lock_guard<std::mutex> guard(lock_);  // one request at a time: the pool has one replica
    TranscribeOutput out;
    out.duration = (double)samples.size() / asr::kSampleRate;
    out.chunks_total =
        (int)std::max<std::size_t>(1, (samples.size() + asr::kChunkSamples - 1) / asr::kChunkSamples);
    out.t_load = load_seconds_;

    const double t_vad0 = now_s();
    const VadResult vad =
        compute_vad(samples, options.vad, options.min_speech_s, asr::kChunkSamples);
    const double t_vad = now_s() - t_vad0;
    out.vad_enabled = vad.enabled;
    out.speech_s = vad.speech_s;
    out.skipped_s = vad.skipped_s;

    if (vad.chunks.empty()) {
      // Nothing above the noise floor: silence is a valid answer and it must not
      // pay for a single encoder pass.
      return out;
    }
    const std::vector<int>& keep = vad.chunks;
    out.chunks_used = (int)keep.size();

    const double t_mel0 = now_s();
    std::vector<std::vector<float>> features(keep.size());
    for (std::size_t i = 0; i < keep.size(); ++i) {
      asr::log_mel(samples, (std::size_t)keep[i] * asr::kChunkSamples, filters_, features[i]);
    }
    out.t_mel = now_s() - t_mel0;
    (void)t_vad;

    std::string language = options.language;
    if (language == "auto") {
      StorageView first(Shape{1, asr::kNmels, asr::kChunkFrames}, features[0]);
      auto langs = model_->detect_language(first);
      auto ranked = langs[0].get();
      language = ranked.empty() ? "en" : ranked[0].first;
    }
    const std::size_t language_id = vocab_.id("<|" + language + "|>");
    if (language_id == (std::size_t)-1)
      return out;
    out.language = language;

    const double t_gen0 = now_s();
    std::vector<float> all_features;
    all_features.reserve(keep.size() * asr::kNmels * asr::kChunkFrames);
    for (const auto& block : features)
      all_features.insert(all_features.end(), block.begin(), block.end());
    StorageView batched(
        Shape{(long)keep.size(), (long)asr::kNmels, (long)asr::kChunkFrames}, all_features);
    std::vector<std::vector<std::size_t>> prompts(keep.size(),
                                                  {sot_, language_id, transcribe_});

    ctranslate2::models::WhisperOptions gen_options;
    gen_options.beam_size = options.beam;
    gen_options.max_length = 448;
    gen_options.suppress_blank = true;

    auto futures = model_->generate(batched, prompts, gen_options);

    for (std::size_t index = 0; index < keep.size(); ++index) {
      const int chunk = keep[index];
      const double chunk_base = (double)chunk * 30.0;
      auto result = futures[index].get();
      if (result.sequences.empty() || result.sequences_ids.empty())
        continue;

      const std::vector<std::string>& tokens = result.sequences[0];
      const std::vector<std::size_t>& ids = result.sequences_ids[0];

      Segment current;
      current.chunk = chunk;
      current.start = -1;
      bool open = false;

      auto flush = [&](double end_time) {
        if (!open)
          return;
        current.end = end_time;
        if (current.start < 0)
          current.start = chunk_base;
        if (!current.ids.empty())
          out.segments.push_back(current);
        current = Segment();
        current.chunk = chunk;
        current.start = -1;
        open = false;
      };

      for (std::size_t i = 0; i < tokens.size() && i < ids.size(); ++i) {
        double ts = 0;
        if (timestamp_seconds(tokens[i], ts)) {
          ts += chunk_base;
          if (!open) {
            current.start = ts;
            open = true;
          } else {
            flush(ts);
          }
          continue;
        }
        if (is_special(tokens[i]))
          continue;
        if (!open) {
          current.start = chunk_base;
          open = true;
        }
        current.ids.push_back(ids[i]);
        current.tokens.push_back(tokens[i]);
      }
      if (open)
        flush(std::min(out.duration, (double)(chunk + 1) * 30.0));
    }
    out.t_gen = now_s() - t_gen0;

    align(out, features, keep, language_id);
    return out;
  }

 private:
  void align(TranscribeOutput& out, const std::vector<std::vector<float>>& features,
             const std::vector<int>& keep, std::size_t language_id) {
    const double t_align0 = now_s();
    std::vector<std::vector<double>> token_time(out.segments.size());
    std::vector<bool> complete(out.segments.size(), false);

    std::map<int, std::vector<std::size_t>> by_chunk;
    for (std::size_t i = 0; i < out.segments.size(); ++i)
      by_chunk[out.segments[i].chunk].push_back(i);

    for (auto& entry : by_chunk) {
      const int chunk = entry.first;
      const std::vector<std::size_t>& indices = entry.second;
      const double base = (double)chunk * 30.0;

      // CT2's decoder requires the memory batch (one encoded window) to match the
      // token batch, so the segments of a window cannot be aligned as separate
      // batch entries. They are concatenated into ONE text sequence, exactly like
      // faster-whisper's find_alignment(); the DTW path is split back per segment
      // through the recorded token offsets.
      std::vector<std::size_t> combined;
      std::vector<std::size_t> offsets(indices.size(), 0);
      for (std::size_t b = 0; b < indices.size(); ++b) {
        offsets[b] = combined.size();
        const std::vector<std::size_t>& ids = out.segments[indices[b]].ids;
        combined.insert(combined.end(), ids.begin(), ids.end());
      }
      if (combined.empty())
        continue;

      std::size_t feature_index = 0;
      for (std::size_t i = 0; i < keep.size(); ++i)
        if (keep[i] == chunk)
          feature_index = i;

      StorageView window(Shape{1, asr::kNmels, asr::kChunkFrames}, features[feature_index]);
      auto futures = model_->align(window, {sot_, language_id, transcribe_}, {combined},
                                  {(std::size_t)asr::kChunkFrames}, 7);
      auto aligned = futures[0].get();

      std::vector<double> times(combined.size() + 1, -1.0);
      long previous = -1;
      for (std::size_t k = 0; k < aligned.alignments.size(); ++k) {
        const long text_index = aligned.alignments[k].first;
        if (text_index < 0 || (std::size_t)text_index >= times.size())
          continue;
        if (text_index != previous && times[(std::size_t)text_index] < 0)
          times[(std::size_t)text_index] =
              base + (double)aligned.alignments[k].second * kFrameSeconds;
        previous = text_index;
      }

      for (std::size_t b = 0; b < indices.size(); ++b) {
        const std::size_t si = indices[b];
        const std::size_t length = out.segments[si].ids.size();
        token_time[si].assign(length + 1, -1.0);
        for (std::size_t j = 0; j <= length; ++j) {
          const std::size_t cj = offsets[b] + j;
          if (cj < times.size())
            token_time[si][j] = times[cj];
        }
        bool path_complete = length > 0;
        for (double t : token_time[si])
          if (t < 0)
            path_complete = false;
        complete[si] = path_complete;
      }
    }

    for (std::size_t si = 0; si < out.segments.size(); ++si)
      out.segments[si].words = words_for(out.segments[si], token_time[si], complete[si]);
    out.t_align = now_s() - t_align0;
  }

  static std::vector<Word> words_for(const Segment& segment, std::vector<double> token_times,
                                     bool path_complete) {
    if (!path_complete) {
      // The DTW path did not reach every token (short windows do this). Spread the
      // tokens over the segment proportionally to their length so the words still
      // get a usable, monotonic timing.
      std::fprintf(stderr,
                   "warning: chunk %d segment [%.2f-%.2f]: incomplete DTW path, "
                   "falling back to proportional timing\n",
                   segment.chunk, segment.start, segment.end);
      const double span = std::max(0.04, segment.end - segment.start);
      double total = 0;
      std::vector<double> weight(segment.ids.size(), 1.0);
      for (std::size_t i = 0; i < segment.ids.size() && i < segment.tokens.size(); ++i) {
        weight[i] = (double)std::max<std::size_t>(1, detokenize(segment.tokens[i]).size());
        total += weight[i];
      }
      if (total <= 0)
        total = 1;
      double acc = 0;
      for (std::size_t i = 0; i < segment.ids.size(); ++i) {
        token_times[i] = segment.start + span * (acc / total);
        acc += weight[i];
      }
      token_times[segment.ids.size()] = segment.end;
    }

    // Group byte-level BPE tokens into words: a leading space starts a new word.
    std::vector<Word> words;
    Word word;
    bool open = false;
    std::size_t first_token = 0;

    auto close = [&](std::size_t next_token) {
      if (!open)
        return;
      const std::size_t j0 = std::min(first_token, token_times.size() - 1);
      const std::size_t j1 = std::min(next_token, token_times.size() - 1);
      double start = token_times[j0];
      double end = token_times[j1];
      start = std::max(segment.start, std::min(start, segment.end));
      end = std::min(segment.end, std::max(end, start));
      if (end - start < 0.04)
        end = std::min(segment.end, start + 0.04);
      word.start = start;
      word.end = end;
      words.push_back(word);
      open = false;
    };

    for (std::size_t i = 0; i < segment.tokens.size(); ++i) {
      const std::string piece = detokenize(segment.tokens[i]);
      const bool new_word = !piece.empty() && piece[0] == ' ';
      if (new_word && open)
        close(i);
      if (!open) {
        word = Word();
        word.text = piece;
        first_token = i;
        open = true;
      } else {
        word.text += piece;
      }
    }
    close(segment.tokens.size());

    // A complete DTW path can still be degenerate (every token pinned to the last
    // frame of the window). If most words end up with no duration, spread them over
    // the segment by character length instead of emitting zero-length words.
    if (!words.empty()) {
      std::size_t degenerate = 0;
      for (const auto& item : words)
        if (item.end - item.start < 0.04)
          ++degenerate;
      if (degenerate * 4 > words.size()) {
        double total = 0;
        for (const auto& item : words)
          total += (double)std::max<std::size_t>(1, item.text.size());
        if (total <= 0)
          total = 1;
        const double span = std::max(0.04, segment.end - segment.start);
        double acc = 0;
        for (auto& item : words) {
          const double weight = (double)std::max<std::size_t>(1, item.text.size());
          item.start = segment.start + span * (acc / total);
          acc += weight;
          item.end = segment.start + span * (acc / total);
        }
      }
    }
    return words;
  }

  std::vector<float> filters_;
  Vocab vocab_;
  std::unique_ptr<ctranslate2::models::Whisper> model_;
  std::size_t sot_ = 0;
  std::size_t transcribe_ = 0;
  double load_seconds_ = 0;
  std::mutex lock_;
};

std::string trim_leading_space(const std::string& text) {
  return (!text.empty() && text[0] == ' ') ? text.substr(1) : text;
}

std::string segment_text(const Segment& segment) {
  if (!segment.text.empty())
    return segment.text;
  std::string text;
  for (const auto& word : segment.words) {
    if (!text.empty())
      text += ' ';
    text += trim_leading_space(word.text);
  }
  return text;
}

std::string to_json(const TranscribeOutput& out) {
  std::string body;
  body.reserve(4096);
  body += "{\"ok\":true,\"backend\":\"ctranslate2\",\"language\":\"";
  body += json_escape(out.language.empty() ? "und" : out.language);
  body += "\",\"duration\":";
  char scratch[256];
  std::snprintf(scratch, sizeof(scratch), "%.3f", out.duration);
  body += scratch;
  body += ",\"chunks\":";
  body += std::to_string(out.chunks_used);
  body += ",\"chunks_total\":";
  body += std::to_string(out.chunks_total);
  body += ",\"text\":\"";
  std::string joined;
  for (const auto& segment : out.segments) {
    if (!joined.empty())
      joined += ' ';
    joined += segment_text(segment);
  }
  body += json_escape(joined);
  body += "\",\"segments\":[";
  for (std::size_t s = 0; s < out.segments.size(); ++s) {
    const Segment& segment = out.segments[s];
    if (s)
      body += ',';
    std::snprintf(scratch, sizeof(scratch), "{\"start\":%.3f,\"end\":%.3f,\"text\":\"",
                  segment.start, segment.end);
    body += scratch;
    body += json_escape(segment_text(segment));
    body += "\",\"words\":[";
    for (std::size_t w = 0; w < segment.words.size(); ++w) {
      if (w)
        body += ',';
      std::snprintf(scratch, sizeof(scratch), "{\"word\":\"");
      body += scratch;
      body += json_escape(trim_leading_space(segment.words[w].text));
      std::snprintf(scratch, sizeof(scratch), "\",\"start\":%.3f,\"end\":%.3f}", segment.words[w].start,
                    segment.words[w].end);
      body += scratch;
    }
    body += "]}";
  }
  body += "],\"words\":[";
  bool first_word = true;
  for (const auto& segment : out.segments) {
    for (const auto& word : segment.words) {
      if (!first_word)
        body += ',';
      first_word = false;
      std::snprintf(scratch, sizeof(scratch), "{\"word\":\"");
      body += scratch;
      body += json_escape(trim_leading_space(word.text));
      std::snprintf(scratch, sizeof(scratch), "\",\"start\":%.3f,\"end\":%.3f}", word.start, word.end);
      body += scratch;
    }
  }
  body += "],\"timing\":{";
  std::snprintf(scratch, sizeof(scratch),
                "\"load\":%.2f,\"mel\":%.2f,\"generate\":%.2f,\"align\":%.2f,\"total\":%.2f}",
                out.t_load, out.t_mel, out.t_gen, out.t_align,
                out.t_load + out.t_mel + out.t_gen + out.t_align);
  body += scratch;
  std::snprintf(scratch, sizeof(scratch),
                ",\"vad\":{\"enabled\":%s,\"speech_seconds\":%.2f,\"skipped_seconds\":%.2f}}",
                out.vad_enabled ? "true" : "false", out.speech_s, out.skipped_s);
  body += scratch;
  return body;
}

// ---------------------------------------------------------------- HTTP service

void http_reply(int fd, int status, const std::string& body) {
  const char* reason = status == 200 ? "OK" : status == 404 ? "Not Found" : "Bad Request";
  std::string head = "HTTP/1.1 " + std::to_string(status) + " " + reason +
                     "\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *"
                     "\r\nConnection: close\r\nContent-Length: " +
                     std::to_string(body.size()) + "\r\n\r\n";
  std::string message = head + body;
  std::size_t sent = 0;
  while (sent < message.size()) {
    const ssize_t n = ::send(fd, message.data() + sent, message.size() - sent, 0);
    if (n <= 0)
      return;
    sent += (std::size_t)n;
  }
}

std::string error_json(const std::string& message) {
  return "{\"ok\":false,\"error\":\"" + json_escape(message) + "\"}";
}

/**
 * Job table for asynchronous requests.
 *
 * The bridge runs custom tools with a 60 second ceiling, which is shorter than a
 * long recording needs. A caller can therefore submit with {@code async=1}, get a
 * job id immediately, and collect the transcript with {@code GET /job?id=...}
 * later; the daemon keeps the last jobs in memory.
 */
struct Job {
  std::string state = "running";
  double started = 0;
  double finished = 0;
  std::string payload;
  std::string error;
};

class JobStore {
 public:
  std::string submit() {
    std::lock_guard<std::mutex> guard(lock_);
    const std::string id = "j" + std::to_string(++counter_);
    Job job;
    job.started = now_s();
    jobs_[id] = job;
    trim();
    return id;
  }

  void finish(const std::string& id, const std::string& payload) {
    std::lock_guard<std::mutex> guard(lock_);
    auto it = jobs_.find(id);
    if (it == jobs_.end())
      return;
    it->second.state = "done";
    it->second.payload = payload;
    it->second.finished = now_s();
  }

  void fail(const std::string& id, const std::string& error) {
    std::lock_guard<std::mutex> guard(lock_);
    auto it = jobs_.find(id);
    if (it == jobs_.end())
      return;
    it->second.state = "error";
    it->second.error = error;
    it->second.finished = now_s();
  }

  std::string lookup(const std::string& id) {
    std::lock_guard<std::mutex> guard(lock_);
    auto it = jobs_.find(id);
    if (it == jobs_.end())
      return error_json("unknown job: " + id);
    const Job& job = it->second;
    const double elapsed = (job.state == "running" ? now_s() : job.finished) - job.started;
    if (job.state == "done")
      return "{\"ok\":true,\"job\":\"" + id + "\",\"state\":\"done\",\"elapsed\":" +
             std::to_string((int)elapsed) + ",\"result\":" + job.payload + "}";
    if (job.state == "error")
      return "{\"ok\":false,\"job\":\"" + id + "\",\"state\":\"error\",\"elapsed\":" +
             std::to_string((int)elapsed) + ",\"error\":\"" + json_escape(job.error) + "\"}";
    return "{\"ok\":true,\"job\":\"" + id + "\",\"state\":\"running\",\"elapsed\":" +
           std::to_string((int)elapsed) + "}";
  }

 private:
  void trim() {
    while (jobs_.size() > 16)
      jobs_.erase(jobs_.begin());
  }

  std::mutex lock_;
  std::map<std::string, Job> jobs_;
  int counter_ = 0;
};

std::map<std::string, std::string> parse_query(const std::string& target) {
  std::map<std::string, std::string> result;
  const std::size_t mark = target.find('?');
  if (mark == std::string::npos)
    return result;
  std::string query = target.substr(mark + 1);
  std::size_t start = 0;
  while (start <= query.size()) {
    const std::size_t next = query.find('&', start);
    const std::string pair = query.substr(start, next == std::string::npos ? std::string::npos
                                                                          : next - start);
    const std::size_t equal = pair.find('=');
    if (equal != std::string::npos)
      result[pair.substr(0, equal)] = pair.substr(equal + 1);
    if (next == std::string::npos)
      break;
    start = next + 1;
  }
  return result;
}

std::string json_field(const std::string& json, const std::string& key) {
  const std::string needle = "\"" + key + "\"";
  std::size_t at = json.find(needle);
  if (at == std::string::npos)
    return "";
  at = json.find(':', at + needle.size());
  if (at == std::string::npos)
    return "";
  at = json.find('"', at);
  if (at == std::string::npos)
    return "";
  const std::size_t end = json.find('"', at + 1);
  if (end == std::string::npos)
    return "";
  return json.substr(at + 1, end - at - 1);
}

int serve(int argc, char** argv) {
  if (argc < 4) {
    std::fprintf(stderr, "usage: whisper_ct2 --serve <port> <model_dir> <mel_filters.bin> "
                         "[beam=5] [threads=6] [compute=int8] [min_speech_s=0.25]\n");
    return 2;
  }
  const int port = std::atoi(argv[2]);
  const std::string model_dir = argv[3];
  const std::string filters_path = argv[4];
  const std::size_t beam = argc > 5 ? (std::size_t)std::atoi(argv[5]) : 5;
  const int threads = argc > 6 ? std::atoi(argv[6]) : 6;
  const std::string compute = argc > 7 ? argv[7] : "int8";
  const double min_speech = argc > 8 ? std::atof(argv[8]) : 0.25;

  ctranslate2::ComputeType compute_type = ctranslate2::ComputeType::INT8;
  if (compute == "float32")
    compute_type = ctranslate2::ComputeType::FLOAT32;
  else if (compute == "float16")
    compute_type = ctranslate2::ComputeType::FLOAT16;
  else if (compute == "auto")
    compute_type = ctranslate2::ComputeType::DEFAULT;

  Engine engine;
  std::string err;
  if (!engine.load(model_dir, filters_path, threads, compute_type, err)) {
    std::fprintf(stderr, "error: %s\n", err.c_str());
    return 1;
  }

  const int listener = ::socket(AF_INET, SOCK_STREAM, 0);
  if (listener < 0) {
    std::fprintf(stderr, "error: cannot create socket\n");
    return 1;
  }
  int reuse = 1;
  ::setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
  sockaddr_in address{};
  address.sin_family = AF_INET;
  address.sin_port = htons((uint16_t)port);
  address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  if (::bind(listener, (sockaddr*)&address, sizeof(address)) != 0) {
    std::fprintf(stderr, "error: cannot bind 127.0.0.1:%d\n", port);
    ::close(listener);
    return 1;
  }
  if (::listen(listener, 4) != 0) {
    std::fprintf(stderr, "error: cannot listen on 127.0.0.1:%d\n", port);
    ::close(listener);
    return 1;
  }
  std::fprintf(stderr, "ready: http://127.0.0.1:%d/asr model=%s beam=%zu threads=%d load=%.2fs\n",
               port, model_dir.c_str(), beam, threads, engine.load_seconds());
  std::fflush(stderr);

  JobStore jobs;

  for (;;) {
    const int client = ::accept(listener, nullptr, nullptr);
    if (client < 0)
      continue;

    std::string request;
    request.reserve(65536);
    char buffer[16384];
    std::size_t header_end = std::string::npos;
    while (header_end == std::string::npos && request.size() < 2 * 1024 * 1024) {
      const ssize_t n = ::recv(client, buffer, sizeof(buffer), 0);
      if (n <= 0)
        break;
      request.append(buffer, (std::size_t)n);
      header_end = request.find("\r\n\r\n");
    }
    if (header_end == std::string::npos) {
      http_reply(client, 400, error_json("incomplete HTTP request"));
      ::close(client);
      continue;
    }

    const std::string head = request.substr(0, header_end);
    std::string body = request.substr(header_end + 4);
    std::size_t content_length = 0;
    {
      std::string lower = head;
      std::transform(lower.begin(), lower.end(), lower.begin(),
                     [](unsigned char c) { return (char)std::tolower(c); });
      const std::size_t at = lower.find("content-length:");
      if (at != std::string::npos)
        content_length = (std::size_t)std::atol(head.c_str() + at + 15);
    }
    while (body.size() < content_length) {
      const ssize_t n = ::recv(client, buffer, sizeof(buffer), 0);
      if (n <= 0)
        break;
      body.append(buffer, (std::size_t)n);
    }

    const std::size_t line_end = head.find("\r\n");
    const std::string request_line = head.substr(0, line_end);
    std::string method, target;
    {
      const std::size_t first = request_line.find(' ');
      const std::size_t second = request_line.find(' ', first + 1);
      if (first != std::string::npos) {
        method = request_line.substr(0, first);
        target = request_line.substr(first + 1, second == std::string::npos
                                                       ? std::string::npos
                                                       : second - first - 1);
      }
    }
    const std::string path = target.substr(0, target.find('?'));
    const std::map<std::string, std::string> query = parse_query(target);

    if (method == "GET" && path == "/health") {
      std::string payload = "{\"ok\":true,\"backend\":\"ctranslate2\",\"model\":\"" +
                            json_escape(model_dir) + "\",\"beam\":" + std::to_string(beam) +
                            ",\"threads\":" + std::to_string(threads) + ",\"load_seconds\":" +
                            std::to_string((int)engine.load_seconds()) + "}";
      http_reply(client, 200, payload);
      ::close(client);
      continue;
    }
    if (method == "GET" && path == "/job") {
      const std::string id = query.count("id") ? query.at("id") : "";
      if (id.empty()) {
        http_reply(client, 400, error_json("missing id"));
      } else {
        http_reply(client, 200, jobs.lookup(id));
      }
      ::close(client);
      continue;
    }
    if (method != "POST" || path != "/asr") {
      http_reply(client, 404, error_json("unknown endpoint, use GET /health, POST /asr, GET /job"));
      ::close(client);
      continue;
    }

    TranscribeOptions options;
    options.beam = beam;
    options.vad = query.count("vad") ? query.at("vad") != "0" : true;
    options.min_speech_s = query.count("min_speech") ? std::atof(query.at("min_speech").c_str())
                                                     : min_speech;
    options.language = query.count("lang") ? query.at("lang") : "auto";

    std::string wav_path;
    bool temporary = false;
    if (!body.empty() && body[0] == '{') {
      // JSON request: {"wav": "/abs/path", "lang": "id", "beam": 5}
      wav_path = json_field(body, "wav");
      const std::string lang = json_field(body, "lang");
      if (!lang.empty())
        options.language = lang;
      if (wav_path.empty()) {
        http_reply(client, 400, error_json("missing wav path"));
        ::close(client);
        continue;
      }
    } else if (!body.empty() && body.size() > 44) {
      // Raw WAV upload: the client posts bytes so nothing has to be readable by
      // the daemon (the bridge app owns its own storage).
      wav_path = "/data/local/tmp/ct2/.asr-upload.wav";
      std::ofstream sink(wav_path, std::ios::binary);
      sink.write(body.data(), (std::streamsize)body.size());
      sink.close();
      temporary = true;
    } else {
      http_reply(client, 400, error_json("body must be a JSON request or a WAV file"));
      ::close(client);
      continue;
    }

    std::string read_err;
    std::vector<float> samples;
    if (!asr::read_wav_mono16(wav_path, samples, read_err)) {
      http_reply(client, 400, error_json(read_err));
      if (temporary)
        ::unlink(wav_path.c_str());
      ::close(client);
      continue;
    }
    if (temporary)
      ::unlink(wav_path.c_str());

    const bool asynchronous = query.count("async") && query.at("async") != "0";
    if (asynchronous) {
      const std::string id = jobs.submit();
      std::thread([&engine, &jobs, samples, options, id]() {
        try {
          const TranscribeOutput out = engine.run(samples, options);
          jobs.finish(id, to_json(out));
        } catch (const std::exception& e) {
          jobs.fail(id, e.what());
        } catch (...) {
          jobs.fail(id, "transcription failed");
        }
      }).detach();
      http_reply(client, 200,
                 "{\"ok\":true,\"job\":\"" + id + "\",\"state\":\"running\",\"duration_seconds\":" +
                     std::to_string((int)((double)samples.size() / asr::kSampleRate)) + "}");
      ::close(client);
      continue;
    }

    const double started = now_s();
    const TranscribeOutput out = engine.run(samples, options);
    std::string payload = to_json(out);
    std::fprintf(stderr, "request: %.1fs audio -> %.2fs in %.2fs (chunks %d/%d)\n", out.duration,
                 now_s() - started, now_s() - started, out.chunks_used, out.chunks_total);
    std::fflush(stderr);
    http_reply(client, 200, payload);
    ::close(client);
  }
}

}  // namespace

int main(int argc, char** argv) {
  if (argc > 1 && std::string(argv[1]) == "--serve")
    return serve(argc, argv);

  if (argc < 6) {
    std::fprintf(stderr,
                 "usage: whisper_ct2 <model_dir> <wav16k_mono> <lang|auto> <mel_filters.bin> "
                 "<out.json> [beam=5] [threads=4] [compute=int8] [vad=1] [min_speech_s=0.25]\n"
                 "       whisper_ct2 --serve <port> <model_dir> <mel_filters.bin> "
                 "[beam=5] [threads=6] [compute=int8] [min_speech_s=0.25]\n");
    return 2;
  }
  const std::string model_dir = argv[1];
  const std::string wav = argv[2];
  std::string lang = argv[3];
  const std::string filters_path = argv[4];
  const std::string out_path = argv[5];
  const std::size_t beam = argc > 6 ? (std::size_t)std::atoi(argv[6]) : 5;
  const int threads = argc > 7 ? std::atoi(argv[7]) : 4;
  const std::string compute = argc > 8 ? argv[8] : "int8";
  ctranslate2::ComputeType compute_type = ctranslate2::ComputeType::INT8;
  if (compute == "float32")
    compute_type = ctranslate2::ComputeType::FLOAT32;
  else if (compute == "float16")
    compute_type = ctranslate2::ComputeType::FLOAT16;
  else if (compute == "auto")
    compute_type = ctranslate2::ComputeType::DEFAULT;

  std::string err;
  std::vector<float> samples;
  if (!asr::read_wav_mono16(wav, samples, err)) {
    std::fprintf(stderr, "error: %s\n", err.c_str());
    return 1;
  }

  Engine engine;
  if (!engine.load(model_dir, filters_path, threads, compute_type, err)) {
    std::fprintf(stderr, "error: %s\n", err.c_str());
    return 1;
  }

  TranscribeOptions options;
  options.language = lang;
  options.beam = beam;
  options.vad = argc > 9 ? std::atoi(argv[9]) != 0 : true;
  options.min_speech_s = argc > 10 ? std::atof(argv[10]) : 0.25;

  const double started = now_s();
  const TranscribeOutput out = engine.run(samples, options);
  const double wall = now_s() - started;

  std::fprintf(stderr,
               "timing: load=%.2fs mel=%.2fs generate=%.2fs align=%.2fs total=%.2fs "
               "duration=%.2fs chunks=%d/%d speech=%.2fs skipped=%.2fs\n",
               out.t_load, out.t_mel, out.t_gen, out.t_align, wall, out.duration,
               out.chunks_used, out.chunks_total, out.speech_s, out.skipped_s);

  const std::string payload = to_json(out);
  std::ofstream sink(out_path);
  sink << payload << "\n";
  sink.close();
  std::printf("%s\n", payload.c_str());
  return 0;
}
