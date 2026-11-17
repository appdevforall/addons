#!/bin/sh
# Slows a recorded narration, keeping its pitch, with ffmpeg's atempo filter — which sounded better on this voice
# than Rubber Band. The videos use 0.9: halfway between the recording's own pace and 0.8, which was usable but slow.
# Usage: sh slow.sh input.mp3 output.wav [tempo]     — tempo defaults to 0.9
T=${3:-0.9}
ffmpeg -y -i "$1" -af "atempo=$T" -ar 44100 -ac 1 "$2"
