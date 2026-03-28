#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>
#include <dobby.h>
#include <cstdio>
#include <cstring>
#include <cstdint>

#include "sensor_simulator.h"

#define LOG_TAG "NativeHook"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Sensor event types
#define SENSOR_TYPE_STEP_COUNTER 19
#define SENSOR_TYPE_STEP_DETECTOR 18

// Symbol for SensorDevice::poll
static const char* kPollSymbol = "_ZN7android12SensorDevice4pollEP15sensors_eventt";
// Fallback variations
static const char* kPollSymbols[] = {
    "_ZN7android12SensorDevice4pollEP15sensors_eventt",
    "_ZN7android12SensorDevice4pollEPv",
    nullptr
};

typedef int (*PollFunc)(void*, sensors_event_t*, int);

static PollFunc original_poll = nullptr;
static bool hook_installed = false;

extern "C" int hooked_poll(void* device, sensors_event_t* buffer, int count);

static void* get_lib_base() {
    FILE* fp = fopen("/proc/self/maps", "r");
    if (!fp) return nullptr;
    
    char line[512];
    void* base = nullptr;
    
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, "libandroid_sensors.so") && strstr(line, "r-xp")) {
            uint64_t start;
            sscanf(line, "%lx-", &start);
            base = reinterpret_cast<void*>(start);
            ALOGI("libandroid_sensors.so base: %p", base);
            break;
        }
    }
    
    fclose(fp);
    return base;
}

static void* resolve_symbol(const char* symbol) {
    ALOGI("Resolving: %s", symbol);
    
    // Try RTLD_DEFAULT
    void* sym = dlsym(RTLD_DEFAULT, symbol);
    if (sym) {
        ALOGI("Found %s via RTLD_DEFAULT -> %p", symbol, sym);
        return sym;
    }
    
    // Try DobbySymbolResolver
    sym = DobbySymbolResolver("libandroid_sensors.so", symbol);
    if (sym) {
        ALOGI("Found %s via DobbySymbolResolver -> %p", symbol, sym);
        return sym;
    }
    
    // Try with base + offset
    void* base = get_lib_base();
    if (base) {
        ALOGI("Base: %p, would need offset from IDA", base);
    }
    
    ALOGE("Symbol %s not found", symbol);
    return nullptr;
}

static void process_sensor_events(sensors_event_t* events, int count) {
    if (!events || count <= 0) return;
    
    // Get simulator instance
    auto& sim = gait::SensorSimulator::Get();
    
    for (int i = 0; i < count; i++) {
        sensors_event_t& evt = events[i];
        
        // Only process step sensors
        if (evt.type == SENSOR_TYPE_STEP_COUNTER || evt.type == SENSOR_TYPE_STEP_DETECTOR) {
            sim.ProcessSensorEvent(evt);
        }
    }
}

extern "C" int hooked_poll(void* device, sensors_event_t* buffer, int count) {
    int result = 0;
    
    // Call original
    if (original_poll) {
        result = original_poll(device, buffer, count);
    }
    
    // Process sensor data if we got events
    if (result > 0 && buffer && count > 0) {
        process_sensor_events(buffer, result);
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
    
    // Try to install hook
    void* poll_addr = nullptr;
    
    for (int i = 0; kPollSymbols[i] != nullptr; i++) {
        poll_addr = resolve_symbol(kPollSymbols[i]);
        if (poll_addr) break;
    }
    
    if (!poll_addr) {
        ALOGE("Failed to resolve poll symbol!");
        return;
    }
    
    ALOGI("Installing hook at %p", poll_addr);
    
    int ret = DobbyHook(
        poll_addr,
        reinterpret_cast<void*>(&hooked_poll),
        reinterpret_cast<void**>(&original_poll)
    );
    
    if (ret != 0) {
        ALOGE("DobbyHook failed: %d", ret);
        return;
    }
    
    ALOGI("*** Hook installed successfully! ***");
    hook_installed = true;
    
    gait::SensorSimulator::Get().ReloadConfig();
}

}
