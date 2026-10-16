# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

# Declares the project name. The project name can be accessed via ${ PROJECT_NAME},
# Since this is the top level CMakeLists.txt, the project name is also accessible
# with ${CMAKE_PROJECT_NAME} (both CMake variables are in-sync within the top level
# build script scope).
project("llama-android")

#include(FetchContent)
#FetchContent_Declare(
#        llama
#        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp
#        GIT_TAG        master
#)

# Also provides "common"
#FetchContent_MakeAvailable(llama)

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.
#
# In this top level CMakeLists.txt, ${CMAKE_PROJECT_NAME} is used to define
# the target library name; in the sub-module's CMakeLists.txt, ${PROJECT_NAME}
# is preferred for the same purpose.
#

#load local llama.cpp
add_subdirectory(../../../../subprojects/llama.cpp/ build-llama)

# In order to load a library into your app from Java/Kotlin, you must call
# System.loadLibrary() and pass the name of the library defined here;
# for GameActivity/NativeActivity derived applications, the same library name must be
# used in the AndroidManifest.xml file.
add_library(${CMAKE_PROJECT_NAME} SHARED
        # List C/C++ source files with relative paths to this CMakeLists.txt.
        llama-android.cpp)

# Specifies libraries CMake should link to your target library. You
# can link libraries from various origins, such as libraries defined in this
# build script, prebuilt third-party libraries, or Android system libraries.
target_link_libraries(${CMAKE_PROJECT_NAME}
        # List libraries link to the target library
        llama
        common
        android
        log)

# Stamps the wrapper's own source hash into the .so, so PrebuiltAarAbiTest can fail when the
# committed AAR was built from an older llama-android.cpp. CRs are dropped so a Windows checkout
# hashes the same as the test does; editing the .cpp re-runs this configure step.
set(LLAMA_ANDROID_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/llama-android.cpp")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${LLAMA_ANDROID_SOURCE}")
file(READ "${LLAMA_ANDROID_SOURCE}" LLAMA_ANDROID_SOURCE_TEXT)
string(REPLACE "\r" "" LLAMA_ANDROID_SOURCE_TEXT "${LLAMA_ANDROID_SOURCE_TEXT}")
string(SHA256 LLAMA_ANDROID_SOURCE_SHA256 "${LLAMA_ANDROID_SOURCE_TEXT}")
target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        LLAMA_ANDROID_SOURCE_SHA256="${LLAMA_ANDROID_SOURCE_SHA256}")
