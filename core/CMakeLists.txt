cmake_minimum_required(VERSION 3.14)
project(pocketchat_core CXX C) # C is for the vendored sqlite3.c (third_party/sqlite3/)

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

# FR-013's FTS5 relevance search (core/memory). Vendored (see
# third_party/sqlite3/README.md) rather than linked against the OS's system
# SQLite: the Android NDK exposes libsqlite3.so as a linkable stub but does
# NOT bundle sqlite3.h, so this guarantees identical FTS5 availability on
# every API level this app targets instead of depending on NDK header support
# that turned out not to exist.
add_library(pocketchat_sqlite3 STATIC
    third_party/sqlite3/sqlite3.c
)
target_include_directories(pocketchat_sqlite3 PUBLIC third_party/sqlite3)
target_compile_definitions(pocketchat_sqlite3 PUBLIC
    SQLITE_ENABLE_FTS5
    SQLITE_OMIT_LOAD_EXTENSION
)

add_library(pocketchat_inference STATIC
    inference/pocketchat_inference.cpp
)
target_include_directories(pocketchat_inference PUBLIC inference)
target_link_libraries(pocketchat_inference PUBLIC llama)

add_library(pocketchat_memory STATIC
    memory/pocketchat_memory.cpp
)
target_include_directories(pocketchat_memory PUBLIC memory)
target_link_libraries(pocketchat_memory PUBLIC pocketchat_inference PRIVATE pocketchat_sqlite3)

# Standalone: no dependency on pocketchat_inference/llama.cpp, so it stays
# usable (and testable) even before a model is loaded.
add_library(pocketchat_safety STATIC
    safety/pocketchat_safety.cpp
)
target_include_directories(pocketchat_safety PUBLIC safety)

option(POCKETCHAT_BUILD_CLI "Build the standalone chat_cli/memory_cli test tools" ON)
if(POCKETCHAT_BUILD_CLI)
    add_executable(pocketchat_chat_cli tools/chat_cli.cpp)
    target_link_libraries(pocketchat_chat_cli PRIVATE pocketchat_inference)

    add_executable(pocketchat_memory_cli tools/memory_cli.cpp)
    target_link_libraries(pocketchat_memory_cli PRIVATE pocketchat_memory)

    add_executable(pocketchat_safety_cli tools/safety_cli.cpp)
    target_link_libraries(pocketchat_safety_cli PRIVATE pocketchat_safety)
endif()
