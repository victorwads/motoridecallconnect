cmake_minimum_required(VERSION 3.22)
project(appnative)

set(CMAKE_CXX_STANDARD 17)

# Prefer GPU backend on Android when Vulkan toolchain is available.
# If Vulkan/glslc are missing, keep CPU-only build to avoid breaking installDebug.
set(GGML_OPENCL OFF CACHE BOOL "Disable OpenCL backend for Android build" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "Enable Vulkan backend when supported" FORCE)

if(ANDROID)
  include(CheckIncludeFileCXX)
  include(CheckSymbolExists)

  if(NOT Vulkan_GLSLC_EXECUTABLE)
    set(_ndk_glslc "")
    if(CMAKE_ANDROID_NDK)
      if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
        if(CMAKE_HOST_SYSTEM_PROCESSOR STREQUAL "arm64")
          # Current Android NDK ships glslc under darwin-x86_64.
          set(_ndk_glslc "${CMAKE_ANDROID_NDK}/shader-tools/darwin-x86_64/glslc")
          if(NOT EXISTS "${_ndk_glslc}")
            set(_ndk_glslc "${CMAKE_ANDROID_NDK}/shader-tools/darwin-arm64/glslc")
          endif()
        else()
          set(_ndk_glslc "${CMAKE_ANDROID_NDK}/shader-tools/darwin-x86_64/glslc")
        endif()
      elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux")
        set(_ndk_glslc "${CMAKE_ANDROID_NDK}/shader-tools/linux-x86_64/glslc")
      elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
        set(_ndk_glslc "${CMAKE_ANDROID_NDK}/shader-tools/windows-x86_64/glslc.exe")
      endif()
    endif()

    if(EXISTS "${_ndk_glslc}")
      set(Vulkan_GLSLC_EXECUTABLE "${_ndk_glslc}" CACHE FILEPATH "Path to glslc used by ggml Vulkan backend" FORCE)
    endif()
  endif()

  set(_ggml_can_use_opencl OFF)
  find_path(_opencl_include_dir CL/cl.h)
  find_library(_opencl_library OpenCL)
  if(_opencl_include_dir AND _opencl_library)
    if((_opencl_include_dir MATCHES "${CMAKE_ANDROID_NDK}" OR _opencl_include_dir MATCHES "${CMAKE_SYSROOT}") AND
       (_opencl_library MATCHES "${CMAKE_ANDROID_NDK}" OR _opencl_library MATCHES "${CMAKE_SYSROOT}"))
      set(OpenCL_INCLUDE_DIR "${_opencl_include_dir}" CACHE PATH "OpenCL include dir for Android toolchain" FORCE)
      set(OpenCL_INCLUDE_DIRS "${_opencl_include_dir}" CACHE PATH "OpenCL include dirs for Android toolchain" FORCE)
      set(OpenCL_LIBRARY "${_opencl_library}" CACHE FILEPATH "OpenCL library for Android toolchain" FORCE)
      set(OpenCL_LIBRARIES "${_opencl_library}" CACHE FILEPATH "OpenCL libraries for Android toolchain" FORCE)
      set(GGML_OPENCL ON CACHE BOOL "Enable OpenCL backend when supported" FORCE)
      set(_ggml_can_use_opencl ON)
      message(STATUS "GGML OpenCL backend enabled.")
    else()
      message(WARNING "OpenCL detected only on host paths. Skipping OpenCL backend for Android cross-build.")
    endif()
  else()
    message(WARNING "GGML OpenCL backend disabled for this build (OpenCL headers/libs unavailable in Android toolchain).")
  endif()

  find_package(Vulkan COMPONENTS glslc QUIET)
  set(_ggml_can_use_vulkan OFF)
  if(Vulkan_FOUND AND Vulkan_GLSLC_EXECUTABLE)
    unset(GGML_HAS_VULKAN_HPP CACHE)
    set(CMAKE_REQUIRED_INCLUDES ${Vulkan_INCLUDE_DIRS})
    check_include_file_cxx("vulkan/vulkan.hpp" GGML_HAS_VULKAN_HPP)
    unset(CMAKE_REQUIRED_INCLUDES)

    if(NOT GGML_HAS_VULKAN_HPP AND CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
      set(_vulkan_hpp_fallback_include "")
      if(EXISTS "/opt/homebrew/include/vulkan/vulkan.hpp")
        set(_vulkan_hpp_fallback_include "/opt/homebrew/include")
      elseif(EXISTS "/usr/local/include/vulkan/vulkan.hpp")
        set(_vulkan_hpp_fallback_include "/usr/local/include")
      endif()

      if(_vulkan_hpp_fallback_include)
        list(APPEND Vulkan_INCLUDE_DIRS "${_vulkan_hpp_fallback_include}")
        include_directories(SYSTEM "${_vulkan_hpp_fallback_include}")
        unset(GGML_HAS_VULKAN_HPP CACHE)
        set(CMAKE_REQUIRED_INCLUDES ${Vulkan_INCLUDE_DIRS})
        check_include_file_cxx("vulkan/vulkan.hpp" GGML_HAS_VULKAN_HPP)
        unset(CMAKE_REQUIRED_INCLUDES)
        if(GGML_HAS_VULKAN_HPP)
          message(STATUS "Using Vulkan-Hpp headers from ${_vulkan_hpp_fallback_include}")
        endif()
      endif()
    endif()

    if(GGML_HAS_VULKAN_HPP)
      unset(GGML_HAS_VK_PHYSICAL_DEVICE_FEATURES2 CACHE)
      set(CMAKE_REQUIRED_INCLUDES ${Vulkan_INCLUDE_DIRS})
      set(CMAKE_REQUIRED_LIBRARIES ${Vulkan_LIBRARIES})
      check_symbol_exists(vkGetPhysicalDeviceFeatures2 "vulkan/vulkan_core.h" GGML_HAS_VK_PHYSICAL_DEVICE_FEATURES2)
      unset(CMAKE_REQUIRED_INCLUDES)
      unset(CMAKE_REQUIRED_LIBRARIES)

      if(GGML_HAS_VK_PHYSICAL_DEVICE_FEATURES2)
        set(_ggml_can_use_vulkan ON)
      else()
        message(WARNING "Vulkan SDK found but required symbol vkGetPhysicalDeviceFeatures2 is unavailable for this Android API level. Using CPU fallback.")
      endif()
    else()
      message(WARNING "Vulkan found, but vulkan.hpp is unavailable for this Android toolchain. Using CPU fallback.")
    endif()
  endif()

  if(_ggml_can_use_vulkan)
    set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan backend when supported" FORCE)
    message(STATUS "GGML Vulkan backend enabled (Vulkan + glslc detected).")
  else()
    message(WARNING "GGML Vulkan backend disabled for this build. Using CPU backend fallback.")
  endif()

  if(NOT _ggml_can_use_opencl AND NOT _ggml_can_use_vulkan)
    message(STATUS "GPU backends unavailable in this Android toolchain. CPU backend will be used at runtime.")
  endif()
endif()

# Adjust path to third_party/whisper.cpp
# structure: app/src/main/cpp (here) -> main -> src -> app -> root -> third_party
add_subdirectory(${CMAKE_SOURCE_DIR}/../../../../third_party/whisper.cpp whispercpp_build)

if(TARGET ggml-vulkan)
  # Force function-pointer based Vulkan dispatch to avoid unresolved Vulkan 1.1 symbols on older NDK API stubs.
  target_compile_definitions(ggml-vulkan PRIVATE VK_NO_PROTOTYPES)
endif()

add_library(appnative SHARED
  native-lib.cpp
)

target_link_libraries(appnative
  whisper
  log
)
