cmake_minimum_required(VERSION 3.10.2)

project(nativelogdemo C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(native-log SHARED native-log.c)

# liblog provides __android_log_print and the other <android/log.h> calls.
target_link_libraries(native-log log)
