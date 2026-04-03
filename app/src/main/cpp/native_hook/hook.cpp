#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>
#include <dobby.h>
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <cerrno>
#include <sys/types.h>
#include <cinttypes>

#include "sensor_simulator.h"

#define LOG_TAG "NativeHook"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define SENSOR_TYPE_ACCELEROMETER 1
#define SENSOR_TYPE_LINEAR_ACCELERATION 10
#define SENSOR_TYPE_STEP_COUNTER 19
#define SENSOR_TYPE_STEP_DETECTOR 18

#define EVENT_SIZE 0x68

typedef void (*SendObjectsFunc)(long*, void*, long, long);
static SendObjectsFunc original_send_objects = nullptr;
static bool send_objects_hook_installed = false;
static bool route_simulation_active = false;
static uint64_t send_objects_offset = 0;

#define ALOGI_TO_FILE(...) ALOGI(__VA_ARGS__)
#define ALOGE_TO_FILE(...) ALOGE(__VA_ARGS__)

void setRouteSimulationActive(bool active) {
    route_simulation_active = active;
    ALOGI_TO_FILE("Route simulation: %s", active ? "ACTIVE" : "INACTIVE");
    if (!active) {
        gait::SensorSimulator::Get().UpdateParams(120.0f, 0, false);
    }
}

static void process_sensor_event(void* event) {
    if (!event || !route_simulation_active) return;

    int type = *(int*)((char*)event + 0x08);
    int64_t timestamp = *(int64_t*)((char*)event + 0x10);
    float data0 = *(float*)((char*)event + 0x18);
    float data1 = *(float*)((char*)event + 0x1C);
    float data2 = *(float*)((char*)event + 0x20);

    sensors_event_t se;
    memset(&se, 0, sizeof(se));
    se.type = type;
    se.timestamp = timestamp;
    se.data[0] = data0;
    se.data[1] = data1;
    se.data[2] = data2;

    gait::SensorSimulator::Get().ProcessSensorEvents(&se, 1);

    if (type == SENSOR_TYPE_STEP_COUNTER) {
        ALOGI("STEP_COUNTER: %.0f", data0);
    } else if (type == SENSOR_TYPE_STEP_DETECTOR) {
        ALOGI("STEP_DETECTOR: %.0f", data0);
    } else if (type == SENSOR_TYPE_ACCELEROMETER) {
        ALOGI("ACCEL: %.2f %.2f %.2f", data0, data1, data2);
    } else if (type == SENSOR_TYPE_LINEAR_ACCELERATION) {
        ALOGI("LINEAR_ACCEL: %.2f %.2f %.2f", data0, data1, data2);
    }
}

extern "C" void hooked_send_objects(long* param_1, void* param_2, long param_3, long param_4) {

    ALOGI("hooked_send_objects called: route_active=%d, param_2=%p, param_3=%ld, param_4=%ld", 
          route_simulation_active ? 1 : 0, param_2, param_3, param_4);

    if (!param_2) {
        ALOGE("param_2 is null!");
        if (original_send_objects) {
            original_send_objects(param_1, param_2, param_3, param_4);
        }
        return;
    }

    int count = (int)param_3;
    
    if (count <= 0 || count > 1000) {
        ALOGE("invalid count: %d", count);
        if (original_send_objects) {
            original_send_objects(param_1, param_2, param_3, param_4);
        }
        return;
    }

    char* ptr = (char*)param_2;
    for (int i = 0; i < count; i++) {
        void* event = ptr + i * EVENT_SIZE;
        uintptr_t addr = (uintptr_t)event;
        if (addr < 0x10000) {
            ALOGE("invalid event ptr: %p", event);
            continue;
        }

        if (route_simulation_active) {
            int type = *(int*)((char*)event + 0x08);
            ALOGI("Processing event type=%d", type);
            
            int64_t timestamp = *(int64_t*)((char*)event + 0x10);
            float data0 = *(float*)((char*)event + 0x18);
            float data1 = *(float*)((char*)event + 0x1C);
            float data2 = *(float*)((char*)event + 0x20);

            sensors_event_t se;
            memset(&se, 0, sizeof(se));
            se.type = type;
            se.timestamp = timestamp;
            se.data[0] = data0;
            se.data[1] = data1;
            se.data[2] = data2;

            gait::SensorSimulator::Get().ProcessSensorEvents(&se, 1);

            *(float*)((char*)event + 0x18) = se.data[0];
            *(float*)((char*)event + 0x1C) = se.data[1];
            *(float*)((char*)event + 0x20) = se.data[2];

            if (type == SENSOR_TYPE_STEP_COUNTER) {
                ALOGI("🔥 STEP_COUNTER (原始) = %.0f -> (修改) = %.0f", data0, se.data[0]);
            } else if (type == SENSOR_TYPE_STEP_DETECTOR) {
                ALOGI("🚶 STEP_DETECTOR (原始) = %.0f -> (修改) = %.0f", data0, se.data[0]);
            } else if (type == SENSOR_TYPE_ACCELEROMETER) {
                ALOGI("📡 ACCELEROMETER (原始) = %.2f %.2f %.2f -> (修改) = %.2f %.2f %.2f", 
                      data0, data1, data2, se.data[0], se.data[1], se.data[2]);
            } else if (type == SENSOR_TYPE_LINEAR_ACCELERATION) {
                ALOGI("📊 LINEAR_ACCEL (原始) = %.2f %.2f %.2f -> (修改) = %.2f %.2f %.2f", 
                      data0, data1, data2, se.data[0], se.data[1], se.data[2]);
            }
        }
    }

    if (!original_send_objects) {
        ALOGE("❌ original_send_objects is null!");
        return;
    }

    original_send_objects(param_1, param_2, param_3, param_4);
}

static void install_send_objects_hook() {
    ALOGI_TO_FILE("Installing BitTube::sendObjects hook...");
    
    void* base = nullptr;
    
    FILE* fp = fopen("/proc/self/maps", "r");
    if (!fp) {
        ALOGE_TO_FILE("Failed to open /proc/self/maps");
        return;
    }
    
    char line[512];
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, "libsensor.so")) {
            uint64_t start;
            sscanf(line, "%lx-", &start);
            base = (void*)start;
            ALOGI_TO_FILE("Found libsensor.so at base=%p", base);
            break;
        }
    }
    fclose(fp);
    
    if (!base) {
        ALOGE_TO_FILE("libsensor.so not found in maps");
        return;
    }
    
    if (send_objects_offset == 0) {
        ALOGE_TO_FILE("SendObjects offset not configured!");
        return;
    }
    
    void* addr = (void*)((char*)base + send_objects_offset);
    ALOGI_TO_FILE("Using BitTube::sendObjects at %p (offset=0x%lx)", addr, send_objects_offset);
    
    if (addr) {
        unsigned char* check = (unsigned char*)addr;
        ALOGI_TO_FILE("First bytes: %02x %02x %02x %02x %02x %02x %02x %02x", 
            check[0], check[1], check[2], check[3], check[4], check[5], check[6], check[7]);
    }
    
    int ret = DobbyHook(addr, (void*)hooked_send_objects, (void**)&original_send_objects);
    
    if (ret == 0) {
        ALOGI_TO_FILE("✅ SendObjects Hook SUCCESS!");
        send_objects_hook_installed = true;
    } else {
        ALOGE("❌ SendObjects DobbyHook failed: %d", ret);
    }
}

extern "C" {

JNIEXPORT void JNICALL 
Java_com_kail_location_xposed_FakeLocState_nativeSetWriteOffset(
    JNIEnv* env, 
    jclass clazz, 
    jlong offset
) {
    send_objects_offset = (uint64_t)offset;
    ALOGI_TO_FILE("JNI: Set send_objects offset: 0x%lx", send_objects_offset);
}

JNIEXPORT void JNICALL 
Java_com_kail_location_xposed_FakeLocState_nativeSetRouteSimulation(
    JNIEnv* env, 
    jclass clazz, 
    jboolean active,
    jfloat spm,
    jint mode
) {
    bool isActive = (active != JNI_FALSE);
    ALOGI_TO_FILE("JNI: Set route simulation: active=%d, spm=%.2f, mode=%d", 
          isActive ? 1 : 0, spm, mode);
    
    if (isActive) {
        setRouteSimulationActive(true);
        gait::SensorSimulator::Get().UpdateParams(spm, mode, true);
    } else {
        setRouteSimulationActive(false);
    }
}

JNIEXPORT void JNICALL 
Java_com_kail_location_xposed_FakeLocState_nativeSetGaitParams(
    JNIEnv* env, 
    jclass clazz, 
    jfloat spm, 
    jint mode, 
    jboolean enable
) {
    ALOGI_TO_FILE("JNI: Set gait params spm=%.2f, mode=%d, enable=%d", spm, mode, enable ? 1 : 0);
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
    ALOGI_TO_FILE("JNI: init hook");

    gait::SensorSimulator::Get().Init();
    
    if (send_objects_offset != 0) {
        install_send_objects_hook();
    }
    
    gait::SensorSimulator::Get().ReloadConfig();
}

}
