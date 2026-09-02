cmake_minimum_required(VERSION 3.22.1)
project(foresight_gopro_ingress)

set(FFMPEG_ROOT "${CMAKE_CURRENT_LIST_DIR}/third_party/ffmpeg/arm64-v8a")

add_library(avformat STATIC IMPORTED)
set_target_properties(avformat PROPERTIES IMPORTED_LOCATION "${FFMPEG_ROOT}/lib/libavformat.a")

add_library(avcodec STATIC IMPORTED)
set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION "${FFMPEG_ROOT}/lib/libavcodec.a")

add_library(avutil STATIC IMPORTED)
set_target_properties(avutil PROPERTIES IMPORTED_LOCATION "${FFMPEG_ROOT}/lib/libavutil.a")

add_library(foresight_gopro_ingress SHARED gopro_rtmp_ingress.cpp)
target_include_directories(foresight_gopro_ingress PRIVATE "${FFMPEG_ROOT}/include")
target_compile_features(foresight_gopro_ingress PRIVATE cxx_std_20)
target_link_libraries(
    foresight_gopro_ingress
    PRIVATE
    avformat
    avcodec
    avutil
    android
    log
    z
    dl
    m
)
