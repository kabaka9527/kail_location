#include "sensor_simulator.h"

#include <android/log.h>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>

#define LOG_TAG "SensorSim"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace gait {

SensorSimulator& SensorSimulator::Get() {
    static SensorSimulator instance;
    return instance;
}

void SensorSimulator::Init() {
    if (initialized_.load()) {
        ALOGD("Already initialized");
        return;
    }
    
    config_.steps_per_minute = 120.0f;
    config_.mode = GaitMode::Walk;
    config_.enable = true;
    current_spm_ = config_.steps_per_minute;
    target_spm_ = config_.steps_per_minute;
    
    initialized_.store(true);
    ALOGI("SensorSimulator initialized");
}

void SensorSimulator::UpdateParams(float spm, int mode, bool enable) {
    config_.steps_per_minute = spm;
    config_.mode = static_cast<GaitMode>(mode);
    config_.enable = enable;
    
    if (spm <= 0.0f) {
        spm = ModeDefaultSpm(config_.mode);
    }
    if (spm < 30.0f) spm = 30.0f;
    if (spm > 300.0f) spm = 300.0f;
    
    target_spm_ = spm;
    ALOGI("Updated params: spm=%.2f, mode=%d, enable=%d", spm, mode, enable ? 1 : 0);
}

GaitConfig SensorSimulator::GetConfig() const {
    return config_;
}

float SensorSimulator::ModeDefaultSpm(GaitMode m) {
    switch (m) {
        case GaitMode::Walk: return 120.0f;
        case GaitMode::Run: return 165.0f;
        case GaitMode::FastRun: return 200.0f;
        default: return 120.0f;
    }
}

double SensorSimulator::NextSignedNoise(double amplitude) {
    uint64_t x = rng_state_;
    x ^= x >> 12;
    x ^= x << 25;
    x ^= x >> 27;
    rng_state_ = x;
    uint64_t r = x * 2685821657736338717ULL;
    double u = (r >> 11) * (1.0 / 9007199254740992.0);
    double v = (u * 2.0) - 1.0;
    return v * amplitude;
}

void SensorSimulator::EnsureInitialized(int64_t ts_ns) {
    if (last_ts_ns_ != 0) return;
    
    last_ts_ns_ = ts_ns;
    phase_ = 0.0;
    step_counter_ = 0.0;
    step_phase_acc_ = 0.0;
    
    uint64_t seed = static_cast<uint64_t>(ts_ns) ^ 0x9E3779B97F4A7C15ULL;
    if (seed == 0) seed = 0xCAFEBABEULL;
    rng_state_ = seed;
    
    current_spm_ = target_spm_;
    ALOGD("Initialized with timestamp: %lld", (long long)ts_ns);
}

void SensorSimulator::SmoothStepRate(double dt) {
    const double tau = 1.2;
    const double alpha = 1.0 - std::exp(-dt / tau);
    current_spm_ = static_cast<float>(current_spm_ + (target_spm_ - current_spm_) * alpha);
}

void SensorSimulator::AdvancePhase(double dt) {
    const double sps = static_cast<double>(current_spm_) / 60.0;
    const double omega = kTwoPi * sps;
    
    double omega_jitter = omega * (1.0 + NextSignedNoise(0.015));
    
    phase_ += omega_jitter * dt;
    if (phase_ > 1e9) phase_ = std::fmod(phase_, kTwoPi);
    
    step_phase_acc_ += sps * dt;
}

void SensorSimulator::ApplyAccelerometer(sensors_event_t& e, double dt) {
    (void)dt;
    
    const double sps = static_cast<double>(current_spm_) / 60.0;
    const double omega = kTwoPi * sps;
    
    double a = 1.0, b = 1.0, c = 1.0;
    switch (config_.mode) {
        case GaitMode::Walk:
            a = 1.0; b = 1.0; c = 1.0;
            break;
        case GaitMode::Run:
            a = 1.15; b = 1.10; c = 1.10;
            break;
        case GaitMode::FastRun:
            a = 1.30; b = 1.20; c = 1.20;
            break;
    }
    
    double t = static_cast<double>(e.timestamp) * kNsToSec;
    
    double base_x = std::sin(omega * t) * 0.6 * a;
    double base_y = std::cos(omega * t) * 0.4 * b;
    double base_z = 9.8 + std::sin(2.0 * omega * t) * 0.2 * c;
    
    double noise_scale = 0.05;
    double x = base_x * (1.0 + NextSignedNoise(noise_scale));
    double y = base_y * (1.0 + NextSignedNoise(noise_scale));
    double z = base_z * (1.0 + NextSignedNoise(noise_scale));
    
    e.data[0] = static_cast<float>(x);
    e.data[1] = static_cast<float>(y);
    e.data[2] = static_cast<float>(z);
}

void SensorSimulator::ApplyStepCounter(sensors_event_t& e, double dt) {
    const double sps = static_cast<double>(current_spm_) / 60.0;
    step_counter_ += sps * dt;
    
    double drift = NextSignedNoise(0.0005) * (sps * dt);
    step_counter_ += drift;
    
    if (step_counter_ < 0.0) step_counter_ = 0.0;
    e.data[0] = static_cast<float>(step_counter_);
}

void SensorSimulator::ApplyStepDetector(sensors_event_t& e, double dt) {
    e.data[0] = 0.0f;
    
    int triggers = 0;
    while (step_phase_acc_ >= 1.0) {
        step_phase_acc_ -= 1.0;
        triggers++;
    }
    
    if (triggers > 0) {
        e.data[0] = 1.0f;
    }
    
    double micro = NextSignedNoise(0.002);
    step_phase_acc_ += micro * dt;
}

void SensorSimulator::ProcessSensorEvents(sensors_event_t* events, size_t count) {
    if (!events || count == 0) return;
    if (!config_.enable) return;
    if (!initialized_.load()) {
        Init();
    }
    
    int64_t last_ts = events[count - 1].timestamp;
    EnsureInitialized(last_ts);
    
    for (size_t i = 0; i < count; i++) {
        sensors_event_t& e = events[i];
        
        int64_t ts = e.timestamp;
        int64_t delta_ns = ts - last_ts_ns_;
        last_ts_ns_ = ts;
        
        double dt = static_cast<double>(delta_ns) * kNsToSec;
        if (dt < 0.0) dt = 0.0;
        if (dt > kMaxDeltaSec) dt = kMaxDeltaSec;
        
        SmoothStepRate(dt);
        AdvancePhase(dt);
        
        switch (e.type) {
            case TYPE_ACCELEROMETER:
                ApplyAccelerometer(e, dt);
                break;
            case TYPE_STEP_COUNTER:
                ApplyStepCounter(e, dt);
                break;
            case TYPE_STEP_DETECTOR:
                ApplyStepDetector(e, dt);
                break;
            default:
                break;
        }
    }
}

void SensorSimulator::ProcessSensorEvent(sensors_event_t& e) {
    if (!config_.enable) return;
    if (!initialized_.load()) {
        Init();
    }
    
    int64_t ts = e.timestamp;
    int64_t delta_ns = ts - last_ts_ns_;
    last_ts_ns_ = ts;
    
    double dt = static_cast<double>(delta_ns) * kNsToSec;
    if (dt < 0.0) dt = 0.0;
    if (dt > kMaxDeltaSec) dt = kMaxDeltaSec;
    
    SmoothStepRate(dt);
    AdvancePhase(dt);
    
    switch (e.type) {
        case TYPE_ACCELEROMETER:
            ApplyAccelerometer(e, dt);
            break;
        case TYPE_STEP_COUNTER:
            ApplyStepCounter(e, dt);
            break;
        case TYPE_STEP_DETECTOR:
            ApplyStepDetector(e, dt);
            break;
        default:
            break;
    }
}

bool SensorSimulator::ReloadConfig() {
    FILE* fp = std::fopen(kConfigPath, "re");
    if (!fp) {
        ALOGD("Config file not found: %s", kConfigPath);
        return false;
    }
    
    GaitConfig new_config{};
    new_config.steps_per_minute = 120.0f;
    new_config.mode = GaitMode::Walk;
    new_config.enable = true;
    
    char line[256];
    while (std::fgets(line, sizeof(line), fp)) {
        char* nl = std::strchr(line, '\n');
        if (nl) *nl = '\0';
        if (line[0] == '\0') continue;
        
        char key[64] = {};
        char val[128] = {};
        if (std::sscanf(line, "%63[^=]=%127s", key, val) != 2) continue;
        
        if (std::strcmp(key, "steps_per_minute") == 0) {
            float spm = std::strtof(val, nullptr);
            if (spm > 0.0f) new_config.steps_per_minute = spm;
        } else if (std::strcmp(key, "mode") == 0) {
            if (std::strcmp(val, "walk") == 0) new_config.mode = GaitMode::Walk;
            else if (std::strcmp(val, "run") == 0) new_config.mode = GaitMode::Run;
            else if (std::strcmp(val, "fast_run") == 0) new_config.mode = GaitMode::FastRun;
        } else if (std::strcmp(key, "enable") == 0) {
            new_config.enable = (std::atoi(val) != 0);
        }
    }
    
    std::fclose(fp);
    
    config_ = new_config;
    target_spm_ = config_.steps_per_minute;
    
    ALOGI("Config reloaded: spm=%.2f, mode=%d, enable=%d",
          config_.steps_per_minute, static_cast<int>(config_.mode), config_.enable ? 1 : 0);
    
    return true;
}

}  // namespace gait
