cmake_minimum_required(VERSION 3.10.2)

project(nativelogdemo CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(native-log SHARED native-log.cpp)

# liblog provides __android_log_write and the other <android/log.h> calls.
target_link_libraries(native-log log)
