cmake_minimum_required(VERSION 3.10)
project(kail_gait_sim C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)

find_package(dobby CONFIG REQUIRED)

# Original gait simulation library (JNI bridge for Kotlin)
add_library(kail_gait_sim SHARED
    simulate.cpp
    config.cpp
    gait_jni.cpp
)

target_include_directories(kail_gait_sim PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(kail_gait_sim PRIVATE ${log-lib} dobby::dobby)

# Native hook library for system_server process
add_library(kail_native_hook SHARED
    native_hook/sensor_simulator.cpp
    native_hook/hook.cpp
)

target_include_directories(kail_native_hook PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(kail_native_hook PRIVATE ${log-lib} dobby::dobby)

set_target_properties(kail_native_hook PROPERTIES
    OUTPUT_NAME "kail_native_hook"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
)
