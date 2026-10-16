#!/usr/bin/env bash
set -euo pipefail

# macOS: native typography and 60fps motion, plus an original light instrumental.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROMO="$ROOT/promo"
WORK="$PROMO/.render"
mkdir -p "$WORK"

if [[ ! -f "$PROMO/music/bucea-focus-original.wav" ]]; then
  python3 "$PROMO/music/build_music.py"
fi

swiftc -O "$PROMO/render_promo.swift" -o "$WORK/render_landscape"
"$WORK/render_landscape" "$ROOT" "$WORK/landscape-silent.mp4" --stills
"$WORK/render_landscape" "$ROOT" "$WORK/landscape-silent.mp4"

ffmpeg -y -loglevel error \
  -i "$WORK/landscape-silent.mp4" -i "$PROMO/music/bucea-focus-original.wav" -i "$PROMO/cover.png" \
  -map 0:v:0 -map 1:a:0 -map 2:v:0 \
  -c:v:0 copy -c:v:1 mjpeg -q:v:1 2 -pix_fmt:v:1 yuvj420p \
  -disposition:v:1 attached_pic -metadata:s:v:1 title="宣传片封面" \
  -metadata:s:v:1 comment="Cover (front)" \
  -c:a aac -b:a 192k -ar 48000 -t 45 \
  -metadata title="北建大图书馆选座 · 把时间，留给专注。" \
  -metadata comment="Original motion graphics and instrumental; Android app demonstration." \
  -movflags +faststart "$PROMO/bucea-library-promo-landscape.mp4"

echo "Created: $PROMO/bucea-library-promo-landscape.mp4"
