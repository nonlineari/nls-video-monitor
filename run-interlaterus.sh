#!/usr/bin/env bash
# InterlaterusDesktop — vertical stack: watch Downloads → mint blockcode NFT → catalog
set -euo pipefail
APP_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$APP_DIR"
export FFMPEG_PATH="${FFMPEG_PATH:-/usr/bin/ffmpeg}"
export FFPROBE_PATH="${FFPROBE_PATH:-/usr/bin/ffprobe}"
export PATH="/usr/bin:/usr/local/bin:${HOME}/.local/bin:${PATH}"

case "${1:-watch}" in
  watch)
    exec /usr/bin/python3 "$APP_DIR/interlaterus_desktop.py" watch
    ;;
  list)
    exec /usr/bin/python3 "$APP_DIR/interlaterus_desktop.py" list
    ;;
  export)
    exec /usr/bin/python3 "$APP_DIR/interlaterus_desktop.py" export
    ;;
  mint)
    shift || true
    exec /usr/bin/python3 "$APP_DIR/interlaterus_desktop.py" mint "$@"
    ;;
  *)
    echo "Usage: $0 [watch|list|export|mint --file PATH --url URL --title TITLE]" >&2
    exit 1
    ;;
esac