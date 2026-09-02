#include <jni.h>

#include <atomic>
#include <cstdint>
#include <limits>
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
constexpr jint kH264Avcc = 0;
constexpr jint kH264AnnexB = 1;
constexpr jint kH264Unknown = 2;
constexpr jint kAacRaw = 0;
constexpr jint kAacAdts = 1;
constexpr jint kAacUnknown = 2;
constexpr int64_t kDiagnosticIntervalUs = 1'000'000;
constexpr jlong kTimestampUnavailable = std::numeric_limits<jlong>::min();

std::atomic_bool g_stop_requested{false};
std::atomic_bool g_running{false};
std::atomic<jlong> g_next_generation{0};

struct StreamDiagnostics {
  jint stream_index = -1;
  bool config_ready = false;
  jint extradata_bytes = 0;
  jint time_base_numerator = 0;
  jint time_base_denominator = 0;
  jint first_dimension = 0;
  jint second_dimension = 0;
  jint representation = kH264Unknown;
  jint nal_length_size = 0;
};

struct MediaDiagnostics {
  jlong generation_id = 0;
  StreamDiagnostics video;
  StreamDiagnostics audio;
  jlong video_packet_count = 0;
  jlong audio_packet_count = 0;
  jlong video_keyframe_count = 0;
  jlong last_video_pts_us = kTimestampUnavailable;
  jlong last_video_dts_us = kTimestampUnavailable;
  jlong last_audio_pts_us = kTimestampUnavailable;
  jlong last_audio_dts_us = kTimestampUnavailable;
  jint last_video_packet_bytes = 0;
  jint last_audio_packet_bytes = 0;
};

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

void emit_media_diagnostics(JNIEnv* env, jobject receiver, jmethodID method, const MediaDiagnostics& diagnostics) {
  env->CallVoidMethod(
      receiver,
      method,
      diagnostics.generation_id,
      static_cast<jboolean>(diagnostics.video.config_ready),
      diagnostics.video.extradata_bytes,
      diagnostics.video.stream_index,
      diagnostics.video.time_base_numerator,
      diagnostics.video.time_base_denominator,
      diagnostics.video.first_dimension,
      diagnostics.video.second_dimension,
      static_cast<jboolean>(diagnostics.audio.config_ready),
      diagnostics.audio.extradata_bytes,
      diagnostics.audio.stream_index,
      diagnostics.audio.time_base_numerator,
      diagnostics.audio.time_base_denominator,
      diagnostics.audio.first_dimension,
      diagnostics.audio.second_dimension,
      diagnostics.video_packet_count,
      diagnostics.audio_packet_count,
      diagnostics.video_keyframe_count,
      diagnostics.last_video_pts_us,
      diagnostics.last_video_dts_us,
      diagnostics.last_audio_pts_us,
      diagnostics.last_audio_dts_us,
      diagnostics.last_video_packet_bytes,
      diagnostics.last_audio_packet_bytes);
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
}

jbyteArray copy_to_byte_array(JNIEnv* env, const uint8_t* data, jint size) {
  jbyteArray copy = env->NewByteArray(size);
  if (copy == nullptr || size == 0) return copy;
  env->SetByteArrayRegion(copy, 0, size, reinterpret_cast<const jbyte*>(data));
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    env->DeleteLocalRef(copy);
    return nullptr;
  }
  return copy;
}

bool has_annex_b_start_code(const uint8_t* data, int size) {
  return size >= 3 && data[0] == 0 && data[1] == 0 &&
      (data[2] == 1 || (size >= 4 && data[2] == 0 && data[3] == 1));
}

bool parse_avcc_extradata(const uint8_t* data, int size, jint* nal_length_size) {
  if (size < 7 || data[0] != 1) return false;
  *nal_length_size = (data[4] & 0x03) + 1;
  int offset = 5;
  const int sps_count = data[offset++] & 0x1f;
  if (sps_count == 0) return false;
  for (int i = 0; i < sps_count; ++i) {
    if (offset + 2 > size) return false;
    const int nal_size = (data[offset] << 8) | data[offset + 1];
    offset += 2;
    if (nal_size <= 0 || offset + nal_size > size) return false;
    offset += nal_size;
  }
  if (offset >= size) return false;
  const int pps_count = data[offset++];
  if (pps_count == 0) return false;
  for (int i = 0; i < pps_count; ++i) {
    if (offset + 2 > size) return false;
    const int nal_size = (data[offset] << 8) | data[offset + 1];
    offset += 2;
    if (nal_size <= 0 || offset + nal_size > size) return false;
    offset += nal_size;
  }
  return true;
}

bool has_valid_length_prefixed_nals(const uint8_t* data, int size, int nal_length_size) {
  if (size <= 0 || nal_length_size < 1 || nal_length_size > 4) return false;
  int offset = 0;
  int nal_count = 0;
  while (offset + nal_length_size <= size) {
    int nal_size = 0;
    for (int i = 0; i < nal_length_size; ++i) nal_size = (nal_size << 8) | data[offset + i];
    offset += nal_length_size;
    if (nal_size <= 0 || nal_size > size - offset) return false;
    offset += nal_size;
    ++nal_count;
  }
  return nal_count > 0 && offset == size;
}

bool has_valid_audio_specific_config(const uint8_t* data, int size) {
  if (size < 2) return false;
  const int audio_object_type = data[0] >> 3;
  const int sample_rate_index = ((data[0] & 0x07) << 1) | (data[1] >> 7);
  return audio_object_type > 0 && audio_object_type < 32 && sample_rate_index <= 12;
}

bool has_adts_syncword(const uint8_t* data, int size) {
  return size >= 2 && data[0] == 0xff && (data[1] & 0xf6) == 0xf0;
}

void emit_video_format(
    JNIEnv* env,
    jobject receiver,
    jmethodID method,
    jlong generation_id,
    const AVStream* stream,
    const StreamDiagnostics& diagnostics) {
  jbyteArray extradata = copy_to_byte_array(env, stream->codecpar->extradata, stream->codecpar->extradata_size);
  if (extradata == nullptr) return;
  jstring codec_name = env->NewStringUTF(avcodec_get_name(stream->codecpar->codec_id));
  env->CallVoidMethod(
      receiver,
      method,
      generation_id,
      diagnostics.stream_index,
      diagnostics.first_dimension,
      diagnostics.second_dimension,
      diagnostics.time_base_numerator,
      diagnostics.time_base_denominator,
      extradata,
      diagnostics.representation,
      diagnostics.nal_length_size,
      codec_name);
  env->DeleteLocalRef(extradata);
  env->DeleteLocalRef(codec_name);
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
}

void emit_audio_format(
    JNIEnv* env,
    jobject receiver,
    jmethodID method,
    jlong generation_id,
    const AVStream* stream,
    const StreamDiagnostics& diagnostics) {
  jbyteArray extradata = copy_to_byte_array(env, stream->codecpar->extradata, stream->codecpar->extradata_size);
  if (extradata == nullptr) return;
  jstring codec_name = env->NewStringUTF(avcodec_get_name(stream->codecpar->codec_id));
  env->CallVoidMethod(
      receiver,
      method,
      generation_id,
      diagnostics.stream_index,
      diagnostics.first_dimension,
      diagnostics.second_dimension,
      diagnostics.time_base_numerator,
      diagnostics.time_base_denominator,
      extradata,
      diagnostics.representation,
      codec_name);
  env->DeleteLocalRef(extradata);
  env->DeleteLocalRef(codec_name);
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
}

bool has_usable_config(const AVCodecParameters* parameters, AVRational time_base) {
  return parameters->extradata_size > 0 && time_base.num > 0 && time_base.den > 0;
}

void populate_stream_diagnostics(
    const AVStream* stream,
    StreamDiagnostics* diagnostics,
    jint first_dimension,
    jint second_dimension) {
  diagnostics->stream_index = stream->index;
  diagnostics->extradata_bytes = stream->codecpar->extradata_size;
  diagnostics->time_base_numerator = stream->time_base.num;
  diagnostics->time_base_denominator = stream->time_base.den;
  diagnostics->first_dimension = first_dimension;
  diagnostics->second_dimension = second_dimension;
  diagnostics->config_ready = has_usable_config(stream->codecpar, stream->time_base);
}

void inspect_streams(
    AVFormatContext* format,
    std::string* video_codec,
    jint* width,
    jint* height,
    jfloat* frame_rate,
    std::string* audio_codec,
    jint* sample_rate,
    jint* channels,
    MediaDiagnostics* diagnostics) {
  for (unsigned int index = 0; index < format->nb_streams; ++index) {
    AVStream* stream = format->streams[index];
    AVCodecParameters* parameters = stream->codecpar;
    if (parameters->codec_type == AVMEDIA_TYPE_VIDEO &&
        parameters->codec_id == AV_CODEC_ID_H264 && diagnostics->video.stream_index < 0) {
      *video_codec = avcodec_get_name(parameters->codec_id);
      *width = parameters->width;
      *height = parameters->height;
      const AVRational rate = stream->avg_frame_rate.num > 0 ? stream->avg_frame_rate : stream->r_frame_rate;
      if (rate.num > 0 && rate.den > 0) *frame_rate = static_cast<jfloat>(av_q2d(rate));
      populate_stream_diagnostics(stream, &diagnostics->video, parameters->width, parameters->height);
      if (has_annex_b_start_code(parameters->extradata, parameters->extradata_size)) {
        diagnostics->video.representation = kH264AnnexB;
      } else if (parse_avcc_extradata(
                     parameters->extradata,
                     parameters->extradata_size,
                     &diagnostics->video.nal_length_size)) {
        diagnostics->video.representation = kH264Avcc;
      }
    } else if (parameters->codec_type == AVMEDIA_TYPE_AUDIO &&
               parameters->codec_id == AV_CODEC_ID_AAC && diagnostics->audio.stream_index < 0) {
      *audio_codec = avcodec_get_name(parameters->codec_id);
      *sample_rate = parameters->sample_rate;
      *channels = parameters->ch_layout.nb_channels;
      populate_stream_diagnostics(stream, &diagnostics->audio, parameters->sample_rate, parameters->ch_layout.nb_channels);
      if (has_valid_audio_specific_config(parameters->extradata, parameters->extradata_size)) {
        diagnostics->audio.representation = kAacRaw;
      }
    }
  }
}

jlong timestamp_to_us(int64_t timestamp, AVRational time_base) {
  if (timestamp == AV_NOPTS_VALUE) return kTimestampUnavailable;
  return av_rescale_q(timestamp, time_base, AVRational{1, 1'000'000});
}

void observe_packet(const AVPacket* packet, AVFormatContext* format, MediaDiagnostics* diagnostics) {
  if (packet->stream_index == diagnostics->video.stream_index) {
    ++diagnostics->video_packet_count;
    if ((packet->flags & AV_PKT_FLAG_KEY) != 0) ++diagnostics->video_keyframe_count;
    diagnostics->last_video_packet_bytes = packet->size;
    if (packet->pts != AV_NOPTS_VALUE) {
      diagnostics->last_video_pts_us = timestamp_to_us(packet->pts, format->streams[packet->stream_index]->time_base);
    }
    if (packet->dts != AV_NOPTS_VALUE) {
      diagnostics->last_video_dts_us = timestamp_to_us(packet->dts, format->streams[packet->stream_index]->time_base);
    }
  } else if (packet->stream_index == diagnostics->audio.stream_index) {
    ++diagnostics->audio_packet_count;
    diagnostics->last_audio_packet_bytes = packet->size;
    if (packet->pts != AV_NOPTS_VALUE) {
      diagnostics->last_audio_pts_us = timestamp_to_us(packet->pts, format->streams[packet->stream_index]->time_base);
    }
    if (packet->dts != AV_NOPTS_VALUE) {
      diagnostics->last_audio_dts_us = timestamp_to_us(packet->dts, format->streams[packet->stream_index]->time_base);
    }
  }
}

void emit_encoded_sample(
    JNIEnv* env,
    jobject receiver,
    jmethodID video_method,
    jmethodID audio_method,
    const AVPacket* packet,
    AVFormatContext* format,
    const MediaDiagnostics& diagnostics) {
  const AVStream* stream = format->streams[packet->stream_index];
  const jlong pts_us = timestamp_to_us(packet->pts, stream->time_base);
  const jlong dts_us = timestamp_to_us(packet->dts, stream->time_base);
  jbyteArray data = copy_to_byte_array(env, packet->data, packet->size);
  if (data == nullptr) return;
  if (packet->stream_index == diagnostics.video.stream_index) {
    jint representation = kH264Unknown;
    if (has_annex_b_start_code(packet->data, packet->size)) {
      representation = kH264AnnexB;
    } else if (diagnostics.video.representation == kH264Avcc &&
               has_valid_length_prefixed_nals(packet->data, packet->size, diagnostics.video.nal_length_size)) {
      representation = kH264Avcc;
    }
    env->CallVoidMethod(
        receiver,
        video_method,
        diagnostics.generation_id,
        packet->stream_index,
        data,
        pts_us,
        dts_us,
        static_cast<jboolean>((packet->flags & AV_PKT_FLAG_KEY) != 0),
        representation);
  } else if (packet->stream_index == diagnostics.audio.stream_index) {
    const jint representation = has_adts_syncword(packet->data, packet->size)
        ? kAacAdts
        : (diagnostics.audio.representation == kAacRaw && packet->size > 0 ? kAacRaw : kAacUnknown);
    env->CallVoidMethod(
        receiver,
        audio_method,
        diagnostics.generation_id,
        packet->stream_index,
        data,
        pts_us,
        dts_us,
        representation);
  }
  env->DeleteLocalRef(data);
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
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
  jmethodID diagnostics_method = env->GetMethodID(clazz, "onNativeMediaDiagnostics", "(JZIIIIIIZIIIIIIJJJJJJJII)V");
  jmethodID video_format_method = env->GetMethodID(clazz, "onNativeVideoFormat", "(JIIIII[BIILjava/lang/String;)V");
  jmethodID audio_format_method = env->GetMethodID(clazz, "onNativeAudioFormat", "(JIIIII[BILjava/lang/String;)V");
  jmethodID video_sample_method = env->GetMethodID(clazz, "onNativeVideoSample", "(JI[BJJZI)V");
  jmethodID audio_sample_method = env->GetMethodID(clazz, "onNativeAudioSample", "(JI[BJJI)V");
  if (method == nullptr || diagnostics_method == nullptr || video_format_method == nullptr ||
      audio_format_method == nullptr || video_sample_method == nullptr || audio_sample_method == nullptr) {
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
    MediaDiagnostics diagnostics;
    diagnostics.generation_id = g_next_generation.fetch_add(1) + 1;
    const int info_result = avformat_find_stream_info(format, nullptr);
    if (info_result >= 0) {
      std::string video_codec;
      std::string audio_codec;
      jint width = 0;
      jint height = 0;
      jint sample_rate = 0;
      jint channels = 0;
      jfloat frame_rate = 0.0f;
      inspect_streams(
          format,
          &video_codec,
          &width,
          &height,
          &frame_rate,
          &audio_codec,
          &sample_rate,
          &channels,
          &diagnostics);
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

      if (diagnostics.video.stream_index >= 0) {
        emit_video_format(
            env,
            thiz,
            video_format_method,
            diagnostics.generation_id,
            format->streams[diagnostics.video.stream_index],
            diagnostics.video);
      }
      if (diagnostics.audio.stream_index >= 0) {
        emit_audio_format(
            env,
            thiz,
            audio_format_method,
            diagnostics.generation_id,
            format->streams[diagnostics.audio.stream_index],
            diagnostics.audio);
      }

      emit_media_diagnostics(env, thiz, diagnostics_method, diagnostics);

      AVPacket* packet = av_packet_alloc();
      int read_result = 0;
      int64_t last_diagnostics_us = av_gettime_relative();
      if (packet == nullptr) {
        read_result = AVERROR(ENOMEM);
      }
      while (packet != nullptr && !g_stop_requested.load() && (read_result = av_read_frame(format, packet)) >= 0) {
        observe_packet(packet, format, &diagnostics);
        if (packet->stream_index == diagnostics.video.stream_index || packet->stream_index == diagnostics.audio.stream_index) {
          emit_encoded_sample(
              env,
              thiz,
              video_sample_method,
              audio_sample_method,
              packet,
              format,
              diagnostics);
        }
        const int64_t now_us = av_gettime_relative();
        if (now_us - last_diagnostics_us >= kDiagnosticIntervalUs) {
          emit_media_diagnostics(env, thiz, diagnostics_method, diagnostics);
          last_diagnostics_us = now_us;
        }
        av_packet_unref(packet);  // B1a has copied payloads into JVM-owned arrays before this release.
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
