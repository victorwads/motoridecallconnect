cmake_minimum_required(VERSION 3.22)
project(appnative)

set(CMAKE_CXX_STANDARD 17)

# Prefer GPU backend on Android when Vulkan toolchain is available.
# If Vulkan/glslc are missing, keep CPU-only build to avoid breaking installDebug.
set(GGML_OPENCL OFF CACHE BOOL "Disable OpenCL backend for Android build" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "Enable Vulkan backend when supported" FORCE)

if(ANDROID)
  include(CheckIncludeFileCXX)

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

  find_package(Vulkan COMPONENTS glslc QUIET)
  set(_ggml_can_use_vulkan OFF)
  if(Vulkan_FOUND AND Vulkan_GLSLC_EXECUTABLE)
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
        set(CMAKE_REQUIRED_INCLUDES ${Vulkan_INCLUDE_DIRS})
        check_include_file_cxx("vulkan/vulkan.hpp" GGML_HAS_VULKAN_HPP)
        unset(CMAKE_REQUIRED_INCLUDES)
        if(GGML_HAS_VULKAN_HPP)
          message(STATUS "Using Vulkan-Hpp headers from ${_vulkan_hpp_fallback_include}")
        endif()
      endif()
    endif()

    if(GGML_HAS_VULKAN_HPP)
      set(_ggml_can_use_vulkan ON)
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
endif()

# Adjust path to third_party/whisper.cpp
# structure: app/src/main/cpp (here) -> main -> src -> app -> root -> third_party
add_subdirectory(${CMAKE_SOURCE_DIR}/../../../../third_party/whisper.cpp whispercpp_build)

add_library(appnative SHARED
  native-lib.cpp
)

target_link_libraries(appnative
  whisper
  log
)
