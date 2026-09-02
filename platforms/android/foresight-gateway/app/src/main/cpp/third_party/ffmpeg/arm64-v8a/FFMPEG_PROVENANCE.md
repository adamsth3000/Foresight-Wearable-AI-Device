# FFmpeg provenance for GW1-A

This directory packages the narrow Android `arm64-v8a` FFmpeg interface required
by the GoPro RTMP ingress proof. It is not a general FFmpeg distribution.

## Source and licensing

- Source: [FFmpeg 9.0.1 official source release](https://ffmpeg.org/releases/ffmpeg-9.0.1.tar.xz)
- Target: Android `arm64-v8a` (`aarch64`), API 26
- Toolchain: Android NDK r27d (`27.3.13750724`)
- Build form: static archives
- License result: LGPL 2.1-or-later; GPL disabled; nonfree disabled
- License text: [`FFMPEG-LGPL-2.1-or-later.txt`](FFMPEG-LGPL-2.1-or-later.txt)

## Configured components

The build enables `avformat`, `avcodec`, `avutil`, networking, RTMP and TCP
protocols, the FLV demuxer, and the H.264 and AAC parsers. It disables FFmpeg
programs, documentation, debug symbols, shared libraries, GPL, and nonfree code.

The exact configure invocation was:

```text
./configure \
  --prefix="$STAGE/output/ffmpeg-9.0.1/android-arm64-v8a-api26" \
  --target-os=android \
  --arch=aarch64 \
  --enable-cross-compile \
  --cc="$TOOLCHAIN/bin/aarch64-linux-android26-clang" \
  --cxx="$TOOLCHAIN/bin/aarch64-linux-android26-clang++" \
  --host-cc="$HOST_CC" \
  --ar="$TOOLCHAIN/bin/llvm-ar" \
  --ranlib="$TOOLCHAIN/bin/llvm-ranlib" \
  --strip="$TOOLCHAIN/bin/llvm-strip" \
  --nm="$TOOLCHAIN/bin/llvm-nm" \
  --sysroot="$TOOLCHAIN/sysroot" \
  --disable-everything \
  --enable-avformat --enable-avcodec --enable-avutil \
  --enable-network --enable-protocol=rtmp --enable-protocol=tcp \
  --enable-demuxer=flv --enable-parser=h264 --enable-parser=aac \
  --disable-programs --disable-doc --disable-debug --disable-shared \
  --enable-static --enable-small --disable-asm --disable-gpl --disable-nonfree
```

`$STAGE`, `$TOOLCHAIN`, and `$HOST_CC` are the variables from the preserved
external build procedure; they are not Gradle build inputs. Gradle consumes only
the files packaged in this directory and the Android NDK.

## Packaged artifacts

Only the three archives linked by `gopro_rtmp_ingress.cpp` are packaged:

```text
libavformat.a  B0C1EEA712D056F10C9FB34835ECA28A3E693631215DB6A1544B075CAD9DC845
libavcodec.a   2F0DB4DF88ACD28CA05EB85FC1E8612B2F819A94B9147B2840888DCC28FE5774
libavutil.a    C69695A40D0060E64497EBE53C021AC8C8AF171E6978C2F02795BB8E07D98B91
```

`include/` is the 145-file public header set produced by `make install` from the
same build. No FFmpeg source-tree headers are vendored. Rebuilds require the
same NDK and configure procedure, then `make install`; the repository build does
not require `C:\foresight-native` once these installed artifacts are present.

GW1-A accepts one unauthenticated publisher only while the user explicitly starts
the listener.
