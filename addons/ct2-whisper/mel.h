#pragma once
#include <cstdint>
#include <string>
#include <vector>

namespace asr {

constexpr int kSampleRate = 16000;
constexpr int kNfft = 400;
constexpr int kHop = 160;
constexpr int kNmels = 80;
constexpr int kChunkFrames = 3000;      // 30 s
constexpr int kChunkSamples = 480000;   // 30 s

// Reads a mono 16-bit PCM wav and returns float samples in [-1, 1].
bool read_wav_mono16(const std::string& path, std::vector<float>& out, std::string& err);

// Loads a float32 filterbank of shape [80, 201].
bool load_filterbank(const std::string& path, std::vector<float>& out, std::string& err);

// Computes log-mel features for one 30 s chunk (samples may be shorter; zero padded).
// Output layout: [80, 3000] mel-major, matching CTranslate2's [batch, n_mels, time].
void log_mel(const std::vector<float>& samples, std::size_t offset,
             const std::vector<float>& filters, std::vector<float>& out);

// Dumps features of a whole file (one block per 30 s chunk).
bool write_features(const std::string& wav, const std::string& filterbank,
                    const std::string& out_path, int& chunks, std::string& err);

}  // namespace asr
