#include <jni.h>

#include <atomic>
#include <string>

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
#include "libavutil/dict.h"
#include "libavutil/error.h"
#include "libavutil/time.h"
}

namespace {

constexpr jint kListening = 1;
constexpr jint kPublisherConnected = 2;
constexpr jint kStreamMetadata = 3;
constexpr jint kPublisherDisconnected = 4;
constexpr jint kError = 5;

std::atomic_bool g_stop_requested{false};
std::atomic_bool g_running{false};

int interrupt_callback(void*) {
  return g_stop_requested.load() ? 1 : 0;
}

std::string error_text(int error) {
  char buffer[AV_ERROR_MAX_STRING_SIZE] = {};
  av_strerror(error, buffer, sizeof(buffer));
  return buffer;
}

void emit_event(
    JNIEnv* env,
    jobject receiver,
    jmethodID method,
    jint event,
    const std::string& detail,
    const std::string& video_codec = "",
    jint width = 0,
    jint height = 0,
    jfloat frame_rate = 0.0f,
    const std::string& audio_codec = "",
    jint sample_rate = 0,
    jint channels = 0) {
  jstring detail_value = env->NewStringUTF(detail.c_str());
  jstring video_value = env->NewStringUTF(video_codec.c_str());
  jstring audio_value = env->NewStringUTF(audio_codec.c_str());
  env->CallVoidMethod(
      receiver,
      method,
      event,
      detail_value,
      video_value,
      width,
      height,
      frame_rate,
      audio_value,
      sample_rate,
      channels);
  env->DeleteLocalRef(detail_value);
  env->DeleteLocalRef(video_value);
  env->DeleteLocalRef(audio_value);
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
}

void inspect_streams(
    AVFormatContext* format,
    std::string* video_codec,
    jint* width,
    jint* height,
    jfloat* frame_rate,
    std::string* audio_codec,
    jint* sample_rate,
    jint* channels) {
  for (unsigned int index = 0; index < format->nb_streams; ++index) {
    AVStream* stream = format->streams[index];
    AVCodecParameters* parameters = stream->codecpar;
    if (parameters->codec_type == AVMEDIA_TYPE_VIDEO && video_codec->empty()) {
      *video_codec = avcodec_get_name(parameters->codec_id);
      *width = parameters->width;
      *height = parameters->height;
      const AVRational rate = stream->avg_frame_rate.num > 0 ? stream->avg_frame_rate : stream->r_frame_rate;
      if (rate.num > 0 && rate.den > 0) *frame_rate = static_cast<jfloat>(av_q2d(rate));
    } else if (parameters->codec_type == AVMEDIA_TYPE_AUDIO && audio_codec->empty()) {
      *audio_codec = avcodec_get_name(parameters->codec_id);
      *sample_rate = parameters->sample_rate;
      *channels = parameters->ch_layout.nb_channels;
    }
  }
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_foresight_gateway_gopro_NativeRtmpIngress_nativeRun(
    JNIEnv* env,
    jobject thiz,
    jstring host,
    jint port,
    jstring path) {
  if (g_running.exchange(true)) {
    jclass clazz = env->GetObjectClass(thiz);
    jmethodID method = env->GetMethodID(clazz, "onNativeEvent", "(ILjava/lang/String;Ljava/lang/String;IIFLjava/lang/String;II)V");
    emit_event(env, thiz, method, kError, "An RTMP ingress listener is already active.");
    return;
  }

  g_stop_requested.store(false);
  jclass clazz = env->GetObjectClass(thiz);
  jmethodID method = env->GetMethodID(clazz, "onNativeEvent", "(ILjava/lang/String;Ljava/lang/String;IIFLjava/lang/String;II)V");
  if (method == nullptr) {
    g_running.store(false);
    return;
  }

  const char* host_chars = env->GetStringUTFChars(host, nullptr);
  const char* path_chars = env->GetStringUTFChars(path, nullptr);
  const std::string url = "rtmp://" + std::string(host_chars) + ":" + std::to_string(port) + "/" + path_chars;
  env->ReleaseStringUTFChars(host, host_chars);
  env->ReleaseStringUTFChars(path, path_chars);

  avformat_network_init();
  while (!g_stop_requested.load()) {
    emit_event(env, thiz, method, kListening, url);
    AVFormatContext* format = avformat_alloc_context();
    format->interrupt_callback.callback = interrupt_callback;
    AVDictionary* options = nullptr;
    av_dict_set(&options, "listen", "1", 0);
    const int open_result = avformat_open_input(&format, url.c_str(), nullptr, &options);
    av_dict_free(&options);
    if (open_result < 0) {
      avformat_free_context(format);
      if (!g_stop_requested.load()) {
        emit_event(env, thiz, method, kError, "RTMP listen/open failed: " + error_text(open_result));
        av_usleep(300'000);
      }
      continue;
    }

    emit_event(env, thiz, method, kPublisherConnected, "RTMP publisher connected.");
    const int info_result = avformat_find_stream_info(format, nullptr);
    if (info_result >= 0) {
      std::string video_codec;
      std::string audio_codec;
      jint width = 0;
      jint height = 0;
      jint sample_rate = 0;
      jint channels = 0;
      jfloat frame_rate = 0.0f;
      inspect_streams(format, &video_codec, &width, &height, &frame_rate, &audio_codec, &sample_rate, &channels);
      emit_event(
          env,
          thiz,
          method,
          kStreamMetadata,
          "RTMP stream metadata inspected.",
          video_codec,
          width,
          height,
          frame_rate,
          audio_codec,
          sample_rate,
          channels);

      AVPacket* packet = av_packet_alloc();
      int read_result = 0;
      while (!g_stop_requested.load() && (read_result = av_read_frame(format, packet)) >= 0) {
        av_packet_unref(packet);  // GW1-A proves liveness only; it never decodes or records packets.
      }
      av_packet_free(&packet);
      if (!g_stop_requested.load()) {
        emit_event(env, thiz, method, kPublisherDisconnected, "RTMP publisher disconnected: " + error_text(read_result));
      }
    } else if (!g_stop_requested.load()) {
      emit_event(env, thiz, method, kError, "RTMP stream inspection failed: " + error_text(info_result));
    }
    avformat_close_input(&format);
  }
  avformat_network_deinit();
  g_running.store(false);
}

extern "C" JNIEXPORT void JNICALL
Java_com_foresight_gateway_gopro_NativeRtmpIngress_nativeStop(JNIEnv*, jobject) {
  g_stop_requested.store(true);
}
