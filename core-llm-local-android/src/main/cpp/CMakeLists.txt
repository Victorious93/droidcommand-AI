# Phase 2 / Phase B: builds llama.cpp (pinned by scripts/fetch-llama-cpp.sh) as shared libraries.
# Phase 2 / Phase C: adds the dca_llama_jni shim (llama_jni.cpp) that LlamaCppBackend.kt loads via
# System.loadLibrary. CPU backend only; OpenCL/Vulkan are Phase D.
cmake_minimum_required(VERSION 3.22.1)
project(dca_llama LANGUAGES C CXX)

set(LLAMA_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../third_party/llama.cpp")
if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp missing at ${LLAMA_DIR}; run core-llm-local-android/scripts/fetch-llama-cpp.sh")
endif()

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp")

find_library(log-lib log)

add_library(dca_llama_jni SHARED llama_jni.cpp)
target_link_libraries(dca_llama_jni PRIVATE llama ${log-lib})
set_target_properties(dca_llama_jni PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
