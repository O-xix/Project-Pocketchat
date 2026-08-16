cmake_minimum_required(VERSION 3.14)
project(pocketchat_core CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

# Keep the vendored llama.cpp build lean: we only need the core `llama` library,
# not its examples/tools/tests/server, which pull in extra dependencies (curl,
# nlohmann_json, minja) that a mobile build has no use for.
set(LLAMA_BUILD_COMMON  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP    OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS  OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(pocketchat_inference STATIC
    inference/pocketchat_inference.cpp
)
target_include_directories(pocketchat_inference PUBLIC inference)
target_link_libraries(pocketchat_inference PUBLIC llama)

option(POCKETCHAT_BUILD_CLI "Build the standalone chat_cli test tool" ON)
if(POCKETCHAT_BUILD_CLI)
    add_executable(pocketchat_chat_cli tools/chat_cli.cpp)
    target_link_libraries(pocketchat_chat_cli PRIVATE pocketchat_inference)
endif()
