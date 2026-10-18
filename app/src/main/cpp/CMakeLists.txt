cmake_minimum_required(VERSION 3.22)
project(hven LANGUAGES C CXX)

# llama.cpp, built as static libraries and linked into libhven.so.
set(BUILD_SHARED_LIBS OFF)
# Our minimum CPU (see README): ARMv8.2 with dot-product instructions.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod")
# ggml's own thread pool instead of OpenMP: one less runtime library to ship.
set(GGML_OPENMP OFF)
add_subdirectory(../../../../third_party/llama.cpp llama.cpp)

# Builds libhven.so, which Gradle packages into the APK.
add_library(hven SHARED hven.cpp)
# log: Android's logging library (logcat).
target_link_libraries(hven PRIVATE llama log)
