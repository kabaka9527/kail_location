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
#include <cmath>

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

typedef void (*ConvertToSensorEventFunc)(void* param_1, void* param_2);
static ConvertToSensorEventFunc original_convert_to_sensor_event = nullptr;
static bool convert_to_sensor_event_hook_installed = false;
static uint64_t convert_to_sensor_event_offset = 0x5b420;

static int stepdetectorTrigger = 0;
static int stepcounterTrigger = 0;
static int mSensorHandleStepDetector = -1;
static int mSensorHandleStepCounter = -1;
static int isMocking = 0;
static int isAuthorized = 0;
static float current_spm = 120.0f;
static int step_event_counter = 0;

#define ALOGI_TO_FILE(...) ALOGI(__VA_ARGS__)
#define ALOGE_TO_FILE(...) ALOGE(__VA_ARGS__)

void setRouteSimulationActive(bool active) {
    route_simulation_active = active;
    if (!active) {
        gait::SensorSimulator::Get().UpdateParams(120.0f, 0, 0, false);
    }
}

extern "C" void hooked_send_objects(long* param_1, void* param_2, long param_3, long param_4) {
    if (!param_2) {
        if (original_send_objects) {
            original_send_objects(param_1, param_2, param_3, param_4);
        }
        return;
    }

    int count = (int)param_3;
    
    if (count <= 0 || count > 1000) {
        if (original_send_objects) {
            original_send_objects(param_1, param_2, param_3, param_4);
        }
        return;
    }

    size_t buffer_size = count * EVENT_SIZE;
    
    if (buffer_size > 65536) {
        if (original_send_objects) {
            original_send_objects(param_1, param_2, param_3, param_4);
        }
        return;
    }

    char* heap_buffer = new char[buffer_size];
    memcpy(heap_buffer, param_2, buffer_size);

    char* ptr = heap_buffer;
    for (int i = 0; i < count; i++) {
        void* event = ptr + i * EVENT_SIZE;
        uintptr_t addr = (uintptr_t)event;
        if (addr < 0x10000) {
            continue;
        }

        if (route_simulation_active) {
            int type = *(int*)((char*)event + 0x08);
            
            int64_t timestamp = *(int64_t*)((char*)event + 0x10);
            
            if (type == SENSOR_TYPE_STEP_COUNTER) {
                uint64_t data0 = *(uint64_t*)((char*)event + 0x18);
                
                sensors_event_t se;
                memset(&se, 0, sizeof(se));
                se.type = type;
                se.timestamp = timestamp;
                se.data[0] = (float)data0;
                
                gait::SensorSimulator::Get().ProcessSensorEvents(&se, 1);
                
                *(uint64_t*)((char*)event + 0x18) = (uint64_t)se.data[0];
            } else {
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
            }
        }
    }

    memcpy(param_2, heap_buffer, buffer_size);
    delete[] heap_buffer;

    if (!original_send_objects) {
        return;
    }

    original_send_objects(param_1, param_2, param_3, param_4);
}

extern "C" void hooked_convert_to_sensor_event(void* param_1, void* param_2) {
    if (!param_2) {
        if (original_convert_to_sensor_event) {
            original_convert_to_sensor_event(param_1, param_2);
        }
        return;
    }

    int sensor_type = *(int*)((char*)param_2 + 0x08);

    if (sensor_type == SENSOR_TYPE_STEP_DETECTOR) {
        stepdetectorTrigger = 1;
        mSensorHandleStepDetector = *(int*)((char*)param_2 + 0x04);
//        ALOGI("Got STEP_DETECTOR: handle=%d", mSensorHandleStepDetector);
    } else if (sensor_type == SENSOR_TYPE_STEP_COUNTER) {
        stepcounterTrigger = 1;
        mSensorHandleStepCounter = *(int*)((char*)param_2 + 0x04);
//        ALOGI("Got STEP_COUNTER: handle=%d", mSensorHandleStepCounter);
    } else if (sensor_type == 5) {
        if (mSensorHandleStepDetector == -1) {
            mSensorHandleStepDetector = 0;
        }
        if (mSensorHandleStepCounter == -1) {
            mSensorHandleStepCounter = 0;
        }
    }

    if (original_convert_to_sensor_event) {
        original_convert_to_sensor_event(param_1, param_2);
    }

//    ALOGI("check: isMocking=%d, type=%d, SDT=%d, SSD=%d, SCT=%d, SSC=%d",
//        isMocking, sensor_type, stepdetectorTrigger, mSensorHandleStepDetector,
//        stepcounterTrigger, mSensorHandleStepCounter);
    if ((isMocking != 0) && (sensor_type == 5)) {
        if ((stepdetectorTrigger == 1) && (mSensorHandleStepDetector != -1) &&
            (stepcounterTrigger == 1) && (mSensorHandleStepCounter != -1)) {
            if (step_event_counter < 4) {
                step_event_counter++;
                *(int*)((char*)param_2 + 0x04) = mSensorHandleStepDetector;
                *(int*)((char*)param_2 + 0x08) = 0x12;
                *(float*)((char*)param_2 + 0x18) = 1.0f;
            } else {
                step_event_counter = 0;
                *(int*)((char*)param_2 + 0x04) = mSensorHandleStepCounter;
                *(int*)((char*)param_2 + 0x08) = 0x13;
                *(uint64_t*)((char*)param_2 + 0x18) = 0;
            }
        } else if ((stepdetectorTrigger == 1) && (mSensorHandleStepDetector != -1)) {
            stepdetectorTrigger = 0;
            *(int*)((char*)param_2 + 0x04) = mSensorHandleStepDetector;
            *(int*)((char*)param_2 + 0x08) = 0x12;
            *(float*)((char*)param_2 + 0x18) = 1.0f;
        } else if ((stepcounterTrigger == 1) && (mSensorHandleStepCounter != -1)) {
            stepcounterTrigger = 0;
            *(int*)((char*)param_2 + 0x04) = mSensorHandleStepCounter;
            *(int*)((char*)param_2 + 0x08) = 0x13;
            *(uint64_t*)((char*)param_2 + 0x18) = 0;
        } else {
            stepdetectorTrigger = 1;
            mSensorHandleStepDetector = 0;
            *(int*)((char*)param_2 + 0x04) = 0;
            *(int*)((char*)param_2 + 0x08) = 0x12;
            *(float*)((char*)param_2 + 0x18) = 1.0f;
        }
    }
}

static void install_send_objects_hook() {
    void* base = nullptr;
    
    FILE* fp = fopen("/proc/self/maps", "r");
    if (!fp) {
        return;
    }
    
    char line[512];
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, "libsensor.so")) {
            uint64_t start;
            sscanf(line, "%lx-", &start);
            base = (void*)start;
            break;
        }
    }
    fclose(fp);
    
    if (!base) {
        return;
    }
    
    if (send_objects_offset == 0) {
        return;
    }
    
    void* addr = (void*)((char*)base + send_objects_offset);
    
    int ret = DobbyHook(addr, (void*)hooked_send_objects, (void**)&original_send_objects);
    
    if (ret == 0) {
        send_objects_hook_installed = true;
    }
}

static void install_convert_to_sensor_event_hook() {
    void* base = nullptr;
    
    FILE* fp = fopen("/proc/self/maps", "r");
    if (!fp) {
        return;
    }
    
    char line[512];
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, "libsensorservice.so")) {
            uint64_t start;
            sscanf(line, "%lx-", &start);
            base = (void*)start;
            break;
        }
    }
    fclose(fp);
    
    if (!base) {
        return;
    }
    
    if (convert_to_sensor_event_offset == 0) {
        return;
    }
    
    void* addr = (void*)((char*)base + convert_to_sensor_event_offset);
    
    int ret = DobbyHook(addr, (void*)hooked_convert_to_sensor_event, (void**)&original_convert_to_sensor_event);
    
    if (ret == 0) {
        convert_to_sensor_event_hook_installed = true;
    }
}

extern "C" {

JNIEXPORT void JNICALL 
Java_com_kail_location_xposed_core_FakeLocState_nativeSetWriteOffset(
    JNIEnv* env, 
    jclass clazz, 
    jlong offset
) {
    send_objects_offset = (uint64_t)offset;
}

JNIEXPORT void JNICALL 
Java_com_kail_location_xposed_core_FakeLocState_nativeSetConvertOffset(
    JNIEnv* env, 
    jclass clazz, 
    jlong offset
) {
    convert_to_sensor_event_offset = (uint64_t)offset;
}

JNIEXPORT void JNICALL 
Java_com_kail_location_xposed_core_FakeLocState_nativeSetRouteSimulation(
    JNIEnv* env, 
    jclass clazz, 
    jboolean active,
    jfloat spm,
    jint mode
) {
    bool isActive = (active != JNI_FALSE);
    
    if (isActive) {
        current_spm = spm;
        setRouteSimulationActive(true);
        gait::SensorSimulator::Get().UpdateParams(spm, mode, 0, true);
        isMocking = 1;
        step_event_counter = 0;
    } else {
        setRouteSimulationActive(false);
        isMocking = 0;
    }
}

JNIEXPORT void JNICALL 
Java_com_kail_location_xposed_core_FakeLocState_nativeSetGaitParams(
    JNIEnv* env, 
    jclass clazz, 
    jfloat spm, 
    jint mode,
    jint scheme,
    jboolean enable
) {
    gait::SensorSimulator::Get().UpdateParams(spm, mode, scheme, enable);
}

JNIEXPORT jboolean JNICALL 
Java_com_kail_location_xposed_core_FakeLocState_nativeReloadConfig(
    JNIEnv* env, 
    jclass clazz
) {
    return gait::SensorSimulator::Get().ReloadConfig() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL 
Java_com_kail_location_xposed_core_FakeLocState_nativeSetMocking(
    JNIEnv* env, 
    jclass clazz, 
    jint mocking
) {
    isMocking = (int)mocking;
}

JNIEXPORT void JNICALL 
Java_com_kail_location_xposed_core_FakeLocState_nativeSetAuthorized(
    JNIEnv* env, 
    jclass clazz, 
    jint authorized
) {
    isAuthorized = (int)authorized;
}

JNIEXPORT void JNICALL 
Java_com_kail_location_xposed_core_FakeLocState_nativeInitHook(
    JNIEnv* env, 
    jclass clazz
) {
    gait::SensorSimulator::Get().Init();
    
    if (send_objects_offset != 0) {
        install_send_objects_hook();
    }
    
    if (convert_to_sensor_event_offset != 0) {
        install_convert_to_sensor_event_hook();
    }
    
    gait::SensorSimulator::Get().ReloadConfig();
}

}
