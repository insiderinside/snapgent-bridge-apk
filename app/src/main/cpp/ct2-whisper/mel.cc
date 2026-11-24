#include "mel.h"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>

namespace asr {
namespace {

constexpr double kPi = 3.14159265358979323846;

std::uint32_t rd32(const unsigned char* p) {
  return (std::uint32_t)p[0] | ((std::uint32_t)p[1] << 8) | ((std::uint32_t)p[2] << 16) |
         ((std::uint32_t)p[3] << 24);
}
std::uint16_t rd16(const unsigned char* p) {
  return (std::uint16_t)((std::uint16_t)p[0] | ((std::uint16_t)p[1] << 8));
}

const std::vector<float>& hann_window() {
  static std::vector<float> win;
  if (win.empty()) {
    win.resize(kNfft);
    for (int n = 0; n < kNfft; ++n)
      win[n] = (float)(0.5 - 0.5 * std::cos(2.0 * kPi * n / (double)kNfft));
  }
  return win;
}

const std::vector<float>& dft_tables() {
  static std::vector<float> tbl;
  if (tbl.empty()) {
    const int bins = kNfft / 2 + 1;
    tbl.resize((std::size_t)bins * kNfft * 2);
    for (int k = 0; k < bins; ++k) {
      for (int n = 0; n < kNfft; ++n) {
        const double a = -2.0 * kPi * k * n / (double)kNfft;
        tbl[((std::size_t)k * kNfft + n) * 2 + 0] = (float)std::cos(a);
        tbl[((std::size_t)k * kNfft + n) * 2 + 1] = (float)std::sin(a);
      }
    }
  }
  return tbl;
}

}  // namespace

bool read_wav_mono16(const std::string& path, std::vector<float>& out, std::string& err) {
  std::ifstream f(path, std::ios::binary);
  if (!f) {
    err = "cannot open wav: " + path;
    return false;
  }
  std::vector<unsigned char> buf((std::istreambuf_iterator<char>(f)),
                                 std::istreambuf_iterator<char>());
  if (buf.size() < 44 || std::memcmp(buf.data(), "RIFF", 4) != 0 ||
      std::memcmp(buf.data() + 8, "WAVE", 4) != 0) {
    err = "not a RIFF/WAVE file";
    return false;
  }
  int channels = 0, bits = 0, sample_rate = 0;
  std::size_t pos = 12, data_off = 0, data_len = 0;
  while (pos + 8 <= buf.size()) {
    char id[5] = {0};
    std::memcpy(id, buf.data() + pos, 4);
    const std::uint32_t len = rd32(buf.data() + pos + 4);
    const std::size_t body = pos + 8;
    if (std::memcmp(id, "fmt ", 4) == 0 && body + 16 <= buf.size()) {
      channels = rd16(buf.data() + body + 2);
      sample_rate = (int)rd32(buf.data() + body + 4);
      bits = rd16(buf.data() + body + 14);
    } else if (std::memcmp(id, "data", 4) == 0) {
      data_off = body;
      data_len = len;
      if (body + len > buf.size())
        data_len = buf.size() - body;
    }
    pos = body + len + (len & 1);
  }
  if (data_off == 0 || data_len == 0) {
    err = "no data chunk";
    return false;
  }
  if (channels != 1 || bits != 16) {
    err = "expected mono 16-bit pcm (got channels=" + std::to_string(channels) +
          " bits=" + std::to_string(bits) + ")";
    return false;
  }
  if (sample_rate != kSampleRate) {
    err = "expected 16 kHz (got " + std::to_string(sample_rate) + ")";
    return false;
  }
  const std::size_t n = data_len / 2;
  out.resize(n);
  for (std::size_t i = 0; i < n; ++i)
    out[i] = (float)((std::int16_t)rd16(buf.data() + data_off + i * 2) / 32768.0);
  return true;
}

bool load_filterbank(const std::string& path, std::vector<float>& out, std::string& err) {
  std::ifstream f(path, std::ios::binary);
  if (!f) {
    err = "cannot open filterbank: " + path;
    return false;
  }
  out.resize((std::size_t)kNmels * (kNfft / 2 + 1));
  f.read(reinterpret_cast<char*>(out.data()), (std::streamsize)(out.size() * sizeof(float)));
  if ((std::size_t)f.gcount() != out.size() * sizeof(float)) {
    err = "filterbank has the wrong size";
    return false;
  }
  return true;
}

void log_mel(const std::vector<float>& samples, std::size_t offset,
             const std::vector<float>& filters, std::vector<float>& out) {
  const int bins = kNfft / 2 + 1;
  const std::vector<float>& win = hann_window();
  const std::vector<float>& tbl = dft_tables();

  std::vector<float> buf((std::size_t)kChunkSamples + kNfft, 0.0f);
  for (int i = 0; i < kChunkSamples; ++i) {
    const std::size_t idx = offset + (std::size_t)i;
    if (idx < samples.size())
      buf[kNfft / 2 + (std::size_t)i] = samples[idx];
  }

  out.assign((std::size_t)kNmels * kChunkFrames, 0.0f);
  std::vector<float> frame(kNfft, 0.0f);
  std::vector<float> spec(bins, 0.0f);
  float vmax = -1e30f;

  for (int t = 0; t < kChunkFrames; ++t) {
    const int start = t * kHop;
    for (int n = 0; n < kNfft; ++n)
      frame[n] = buf[start + n] * win[n];

    for (int k = 0; k < bins; ++k) {
      const float* tw = tbl.data() + ((std::size_t)k * kNfft) * 2;
      float re = 0.0f, im = 0.0f;
      for (int n = 0; n < kNfft; ++n) {
        re += frame[n] * tw[n * 2 + 0];
        im += frame[n] * tw[n * 2 + 1];
      }
      spec[k] = re * re + im * im;
    }

    for (int m = 0; m < kNmels; ++m) {
      const float* flt = filters.data() + (std::size_t)m * bins;
      float acc = 0.0f;
      for (int k = 0; k < bins; ++k)
        acc += flt[k] * spec[k];
      float v = std::log10(acc > 1e-10f ? acc : 1e-10f);
      out[(std::size_t)m * kChunkFrames + t] = v;
      if (v > vmax)
        vmax = v;
    }
  }

  const float floor_v = vmax - 8.0f;
  for (std::size_t i = 0; i < out.size(); ++i) {
    float v = out[i];
    if (v < floor_v)
      v = floor_v;
    out[i] = (v + 4.0f) / 4.0f;
  }
}

bool write_features(const std::string& wav, const std::string& filterbank,
                    const std::string& out_path, int& chunks, std::string& err) {
  std::vector<float> samples, filters;
  if (!read_wav_mono16(wav, samples, err))
    return false;
  if (!load_filterbank(filterbank, filters, err))
    return false;

  chunks = (int)((samples.size() + kChunkSamples - 1) / kChunkSamples);
  if (chunks <= 0)
    chunks = 1;

  std::ofstream out(out_path, std::ios::binary);
  if (!out) {
    err = "cannot write: " + out_path;
    return false;
  }
  std::vector<float> feats;
  for (int c = 0; c < chunks; ++c) {
    log_mel(samples, (std::size_t)c * kChunkSamples, filters, feats);
    out.write(reinterpret_cast<const char*>(feats.data()),
              (std::streamsize)(feats.size() * sizeof(float)));
  }
  return true;
}

}  // namespace asr
