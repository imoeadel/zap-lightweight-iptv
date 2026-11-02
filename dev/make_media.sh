#!/bin/bash
# Generates the test video that dev/fake_xtream.py serves. Needs ffmpeg.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p media
ffmpeg -y -hide_banner -loglevel error -f lavfi -i "testsrc2=size=1280x720:rate=25" -f lavfi -i "sine=frequency=440:sample_rate=48000" \
  -t 20 -c:v libx264 -preset veryfast -b:v 1500k -g 25 -pix_fmt yuv420p -c:a aac -b:a 128k -f mpegts media/live.ts
ffmpeg -y -hide_banner -loglevel error -f lavfi -i "testsrc2=size=1280x720:rate=25" -f lavfi -i "sine=frequency=330:sample_rate=48000" \
  -t 60 -c:v libx264 -preset veryfast -b:v 1500k -g 50 -pix_fmt yuv420p -c:a aac -b:a 128k -movflags +faststart media/vod.mp4
ffmpeg -y -hide_banner -loglevel error -i media/live.ts -an -c copy -f mpegts media/live_silent.ts
# A movie with two audio languages and two subtitle languages, for the Options panel.
for i in $(seq 0 2 58); do printf '%d\n00:00:%02d,000 --> 00:00:%02d,900\nEnglish subtitle %d\n\n' $((i+1)) $i $((i+1)) $i; done > media/en.srt
for i in $(seq 0 2 58); do printf '%d\n00:00:%02d,000 --> 00:00:%02d,900\nسطر ترجمة عربي %d\n\n' $((i+1)) $i $((i+1)) $i; done > media/ar.srt
ffmpeg -y -hide_banner -loglevel error -i media/vod.mp4 -f lavfi -i "sine=frequency=550:sample_rate=48000" -i media/en.srt -i media/ar.srt \
  -map 0:v -map 0:a -map 1:a -map 2 -map 3 -shortest -c:v copy -c:a aac -c:s srt \
  -metadata:s:a:0 language=eng -metadata:s:a:1 language=ara -metadata:s:s:0 language=eng -metadata:s:s:1 language=ara media/vod.mkv
