/*
 * Dezzy Project Original (2026)
 * Dezzy (github.com/dezzy-music)
 * Licensed Under GPL-3.0
 *
 * C++20 DSP Core Header - High Precision Audio FFT & Visualizer Spectrum Processor
 */

#pragma once

#include <vector>
#include <complex>
#include <cstdint>
#include <memory>

namespace dezzy::dsp {

struct SpectrumConfig {
    int numBands = 32;
    float minFreq = 20.0f;
    float maxFreq = 20000.0f;
    float smoothingFactor = 0.65f;
    float sampleRate = 44100.0f;
};

class AudioSpectrumProcessor {
public:
    explicit AudioSpectrumProcessor(const SpectrumConfig& config = SpectrumConfig{});
    ~AudioSpectrumProcessor() = default;

    // Disallow copy, allow move
    AudioSpectrumProcessor(const AudioSpectrumProcessor&) = delete;
    AudioSpectrumProcessor& operator=(const AudioSpectrumProcessor&) = delete;
    AudioSpectrumProcessor(AudioSpectrumProcessor&&) noexcept = default;
    AudioSpectrumProcessor& operator=(AudioSpectrumProcessor&&) noexcept = default;

    /**
     * Ingest raw PCM samples and calculate smoothed spectrum magnitude bands.
     * @param pcmSamples 16-bit signed PCM samples.
     * @param count Number of samples.
     * @param outBands Output buffer of size config.numBands.
     */
    void processPcm(const int16_t* pcmSamples, size_t count, float* outBands);

    /**
     * Set dynamic visualizer smoothing (0.0 = instant, 0.99 = heavy dampening).
     */
    void setSmoothing(float factor);

private:
    void computeFft(std::vector<std::complex<float>>& data);

    SpectrumConfig config_;
    std::vector<float> prevBands_;
    std::vector<float> hanningWindow_;
};

} // namespace dezzy::dsp
