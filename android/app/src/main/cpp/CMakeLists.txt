cmake_minimum_required(VERSION 3.22.1)
project(pocketchat_jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Reuse the exact same core/ build the desktop CLI tools (core/tools/*.cpp)
# link against — this native lib is a thin JNI wrapper around it, so we
# don't want those desktop test tools pulled into the mobile build.
set(POCKETCHAT_BUILD_CLI OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../../core pocketchat_core)

add_library(pocketchat_jni SHARED
    jni_bridge.cpp
)

target_link_libraries(pocketchat_jni
    PRIVATE
        # pocketchat_memory PUBLIC-links pocketchat_inference (see
        # core/CMakeLists.txt), but link both explicitly for clarity.
        pocketchat_inference
        pocketchat_memory
        log
)
