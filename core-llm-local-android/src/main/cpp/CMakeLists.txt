# Phase 2 / Phase B: builds llama.cpp (pinned by scripts/fetch-llama-cpp.sh) as shared libraries.
# Phase 2 / Phase C: adds the dca_llama_jni shim (llama_jni.cpp) that LlamaCppBackend.kt loads via
# System.loadLibrary. CPU always; Vulkan on Android when DCA_ENABLE_VULKAN is ON (Phase D, compiled
# only). OpenCL is deferred (see docs/AUDIT_2026-09-05.md, 2026-10-08d).
cmake_minimum_required(VERSION 3.22.1)
project(dca_llama LANGUAGES C CXX)

set(LLAMA_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../third_party/llama.cpp")
if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp missing at ${LLAMA_DIR}; run core-llm-local-android/scripts/fetch-llama-cpp.sh")
endif()

option(DCA_ENABLE_VULKAN "Build llama.cpp's Vulkan backend (Android only)" ON)

if(ANDROID AND DCA_ENABLE_VULKAN)
    # The NDK ships libvulkan.so, vulkan.h and glslc, but not vulkan.hpp or SPIRV-Headers, which
    # ggml-vulkan needs. Both are fetched (pinned) by scripts/fetch-llama-cpp.sh.
    set(DCA_THIRD_PARTY "${CMAKE_CURRENT_LIST_DIR}/../../../third_party")
    set(DCA_VULKAN_HEADERS "${DCA_THIRD_PARTY}/Vulkan-Headers")
    set(DCA_SPIRV_HEADERS "${DCA_THIRD_PARTY}/SPIRV-Headers")
    if(NOT EXISTS "${DCA_VULKAN_HEADERS}/include/vulkan/vulkan.hpp" OR NOT EXISTS "${DCA_SPIRV_HEADERS}/CMakeLists.txt")
        message(FATAL_ERROR "Vulkan-Headers/SPIRV-Headers missing; run core-llm-local-android/scripts/fetch-llama-cpp.sh")
    endif()
    set(DCA_DEPS_PREFIX "${CMAKE_CURRENT_BINARY_DIR}/deps-prefix")
    # SPIRV-Headers is header-only; install it with the host CMake so find_package(CONFIG) works.
    execute_process(
        COMMAND ${CMAKE_COMMAND} -S "${DCA_SPIRV_HEADERS}" -B "${CMAKE_CURRENT_BINARY_DIR}/spirv-headers-build"
                -DCMAKE_INSTALL_PREFIX=${DCA_DEPS_PREFIX}
        RESULT_VARIABLE dca_spirv_cfg OUTPUT_QUIET)
    if(NOT dca_spirv_cfg EQUAL 0)
        message(FATAL_ERROR "configuring SPIRV-Headers failed (${dca_spirv_cfg})")
    endif()
    execute_process(
        COMMAND ${CMAKE_COMMAND} --install "${CMAKE_CURRENT_BINARY_DIR}/spirv-headers-build"
        RESULT_VARIABLE dca_spirv_inst OUTPUT_QUIET)
    if(NOT dca_spirv_inst EQUAL 0)
        message(FATAL_ERROR "installing SPIRV-Headers failed (${dca_spirv_inst})")
    endif()
    list(APPEND CMAKE_PREFIX_PATH "${DCA_DEPS_PREFIX}")
    list(APPEND CMAKE_FIND_ROOT_PATH "${DCA_DEPS_PREFIX}")
    set(Vulkan_INCLUDE_DIR "${DCA_VULKAN_HEADERS}/include" CACHE PATH "" FORCE)
    # ANDROID_HOST_TAG comes from the NDK's android.toolchain.cmake (e.g. linux-x86_64).
    set(Vulkan_GLSLC_EXECUTABLE "${ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}/glslc" CACHE FILEPATH "" FORCE)
    if(NOT EXISTS "${Vulkan_GLSLC_EXECUTABLE}")
        message(FATAL_ERROR "glslc not found at ${Vulkan_GLSLC_EXECUTABLE}")
    endif()
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
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

if(ANDROID AND DCA_ENABLE_VULKAN)
    # ggml-vulkan find_package()s SPIRV-Headers but never adds its include path (it relies on the
    # Vulkan SDK putting spirv/ on a default path), so add it here without patching upstream.
    target_include_directories(ggml-vulkan PRIVATE "${DCA_DEPS_PREFIX}/include")
endif()

add_library(dca_llama_jni SHARED llama_jni.cpp)
target_link_libraries(dca_llama_jni PRIVATE llama)
if(ANDROID)
    find_library(log-lib log)
    target_link_libraries(dca_llama_jni PRIVATE ${log-lib})
else()
    # Host (non-Android) build, used only by scripts/host-smoke-test.sh.
    find_package(JNI REQUIRED)
    target_include_directories(dca_llama_jni PRIVATE ${JNI_INCLUDE_DIRS})
endif()
set_target_properties(dca_llama_jni PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
