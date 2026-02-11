cmake_minimum_required(VERSION 3.22)
project(appnative)

set(CMAKE_CXX_STANDARD 17)

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
