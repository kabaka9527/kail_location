#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>
#include <dobby.h>
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <sys/types.h>

#include "sensor_simulator.h"

#define LOG_TAG "NativeHook"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define SENSOR_TYPE_STEP_COUNTER 19
#define SENSOR_TYPE_STEP_DETECTOR 18

typedef uint64_t (*PollFunc)(void*, void*, uint64_t);

static PollFunc original_poll = nullptr;
static bool hook_installed = false;

extern "C" uint64_t hooked_poll(void* device, void* buffer, uint64_t count);

static void* get_lib_base() {
    FILE* fp = fopen("/proc/self/maps", "r");
    if (!fp) return nullptr;
    
    char line[512];
    void* base = nullptr;
    
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, "libsensorservice.so") && strstr(line, "r-xp")) {
            uint64_t start;
            sscanf(line, "%lx-", &start);
            base = reinterpret_cast<void*>(start);
            ALOGI("libsensorservice.so base: %p", base);
            break;
        }
    }
    
    fclose(fp);
    return base;
}

static void install_poll_hook() {
    void* base = get_lib_base();
    if (!base) {
        ALOGE("Could not find libsensorservice.so base!");
        return;
    }
    
    // Offset for pollFmq function entry point
    static constexpr uint32_t kPollOffset = 0x125c94;
    void* target = reinterpret_cast<char*>(base) + kPollOffset;
    
    ALOGI("Installing pollFmq hook: base=%p target=%p offset=0x%x", base, target, kPollOffset);
    
    int ret = DobbyHook(
        target,
        reinterpret_cast<void*>(&hooked_poll),
        reinterpret_cast<void**>(&original_poll)
    );
    
    if (ret != 0) {
        ALOGE("DobbyHook failed: %d", ret);
        return;
    }
    
    ALOGI("*** Poll hook installed successfully! ***");
    hook_installed = true;
}

static void process_sensor_events(void* buffer, int count) {
    if (!buffer || count <= 0) return;
    
    // Print raw hex for first 128 bytes to verify structure
    unsigned char* p = reinterpret_cast<unsigned char*>(buffer);
    ALOGI("=== RAW BUFFER START ===");
    for (int i = 0; i < 128; i += 16) {
        ALOGI("%02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x",
              p[i], p[i+1], p[i+2], p[i+3], p[i+4], p[i+5], p[i+6], p[i+7],
              p[i+8], p[i+9], p[i+10], p[i+11], p[i+12], p[i+13], p[i+14], p[i+15]);
    }
    ALOGI("=== RAW BUFFER END ===");
    
    // Try to parse first event
    char* evt = reinterpret_cast<char*>(buffer);
    
    int type = *reinterpret_cast<int*>(evt + 0x08);
    int sensor = *reinterpret_cast<int*>(evt + 0x04);
    int64_t timestamp = *reinterpret_cast<int64_t*>(evt + 0x10);
    float* data = reinterpret_cast<float*>(evt + 0x18);
    
    ALOGI("[0] type=%d sensor=%d ts=%ld data=%.2f %.2f %.2f",
          type, sensor, timestamp, data[0], data[1], data[2]);
}

extern "C" uint64_t hooked_poll(void* device, void* buffer, uint64_t count) {
    // Safety check
    if (!buffer || count == 0) {
        if (original_poll) {
            return original_poll(device, buffer, count);
        }
        return 0;
    }
    
    uint64_t result = 0;
    
    if (original_poll) {
        result = original_poll(device, buffer, count);
    }
    
    if (result > 0 && buffer) {
        process_sensor_events(buffer, (int)result);
    }
    
    return result;
}

extern "C" {

JNIEXPORT void JNICALL 
Java_com_kail_location_xposed_FakeLocState_nativeSetGaitParams(
    JNIEnv* env, 
    jclass clazz, 
    jfloat spm, 
    jint mode, 
    jboolean enable
) {
    ALOGI("JNI: Set gait params spm=%.2f, mode=%d, enable=%d", spm, mode, enable ? 1 : 0);
    gait::SensorSimulator::Get().UpdateParams(spm, mode, enable);
}

JNIEXPORT jboolean JNICALL 
Java_com_kail_location_xposed_FakeLocState_nativeReloadConfig(
    JNIEnv* env, 
    jclass clazz
) {
    return gait::SensorSimulator::Get().ReloadConfig() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL 
Java_com_kail_location_xposed_FakeLocState_nativeInitHook(
    JNIEnv* env, 
    jclass clazz
) {
    ALOGI("JNI: nativeInitHook - installing SensorDevice::poll hook");
    
    gait::SensorSimulator::Get().Init();
    install_poll_hook();
    gait::SensorSimulator::Get().ReloadConfig();
}

}
