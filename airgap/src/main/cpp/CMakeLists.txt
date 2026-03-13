cmake_minimum_required(VERSION 3.22.1)
project(airgap_decoder)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(DISABLE_TESTS true)

# OpenCV from downloaded Android SDK
add_library(lib_opencv SHARED IMPORTED)
set_target_properties(
    lib_opencv
    PROPERTIES IMPORTED_LOCATION ${OpenCV_DIR}/libs/${ANDROID_ABI}/libopencv_java4.so
)
set(OPENCV_LIBS "lib_opencv")

include_directories(
    ${OpenCV_DIR}/jni/include
    .
    cfc/libcimbar/src/lib
    cfc/libcimbar/src/third_party_lib
    cfc
)

set(PROJECTS
    cfc/concurrent
    cfc/libcimbar
)

foreach(proj ${PROJECTS})
    add_subdirectory(${proj} build/${proj})
endforeach()

add_library(
    airgap_decoder
    SHARED
    airgap_jni.cpp
    cfc/libcimbar/src/lib/cimbar_js/cimbar_recv_js.cpp
)

find_library(log-lib log)

target_link_libraries(
    airgap_decoder
    cimb_translator
    extractor
    correct_static
    wirehair
    zstd
    ${OPENCV_LIBS}
    ${log-lib}
)

target_compile_options(airgap_decoder PUBLIC "-DZSTD_STATIC_LINKING_ONLY")
