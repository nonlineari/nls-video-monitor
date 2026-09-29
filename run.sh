#!/usr/bin/env bash
set -euo pipefail
APP_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$APP_DIR"

export GRAVITY_DESKTOP_HOME="$APP_DIR"
export GRAVITY_SERVE_URL="${GRAVITY_SERVE_URL:-http://127.0.0.1:8766}"
if [[ -x "$APP_DIR/yt-dlp-run" ]]; then
  export YTDLP_PATH="$APP_DIR/yt-dlp-run"
elif [[ -x "$APP_DIR/yt-dlp" ]]; then
  export YTDLP_PATH="$APP_DIR/yt-dlp"
else
  export YTDLP_PATH="${YTDLP_PATH:-/usr/bin/yt-dlp}"
fi
export FFMPEG_PATH="${FFMPEG_PATH:-/usr/bin/ffmpeg}"
export GRAVITY_IPFS_GATEWAY="${GRAVITY_IPFS_GATEWAY:-https://ipfs.io/ipfs/}"
export GRAVITY_MIN_REQUEST_INTERVAL_SEC="${GRAVITY_MIN_REQUEST_INTERVAL_SEC:-3}"
export GRAVITY_403_COOLDOWN_SEC="${GRAVITY_403_COOLDOWN_SEC:-60}"
export GRAVITY_403_RETRIES="${GRAVITY_403_RETRIES:-2}"
# Human-like yt-dlp sleep (reduces 403 / bot detection)
export GRAVITY_MIN_SLEEP_INTERVAL="${GRAVITY_MIN_SLEEP_INTERVAL:-3}"
export GRAVITY_MAX_SLEEP_INTERVAL="${GRAVITY_MAX_SLEEP_INTERVAL:-12}"
export GRAVITY_SLEEP_REQUESTS="${GRAVITY_SLEEP_REQUESTS:-1}"
export GRAVITY_HTTP_RETRY_SLEEP_MAX="${GRAVITY_HTTP_RETRY_SLEEP_MAX:-60}"
# HKG default: ISP path. VPN is opt-in (UI checkbox or GRAVITY_USE_VPN=1) for geo-restricted / China.
# export GRAVITY_USE_VPN=1                  # bind downloads to Surfshark
# export GRAVITY_VPN_REQUIRED=1             # same, for travel
# export GRAVITY_SOURCE_ADDRESS=10.64.0.8   # pin tunnel IP if auto-detect misses it
# export GRAVITY_FORCE_IPV4=1               # always IPv4
# export GRAVITY_FORCE_IPV6=1               # allow IPv6
# export GRAVITY_PROXY=socks5://127.0.0.1:1080
# export GRAVITY_GEO_PROXY=socks5://127.0.0.1:1080
# Remote PHP viewer base for viewkey= / menu$N shorthand
# export GRAVITY_REMOTE_BASE=https://example.com/videos/
# Optional cap: export GRAVITY_LIMIT_RATE=5M

# Auto-detect browser cookie DB if not set by user
if [[ -z "${GRAVITY_COOKIES_BROWSER:-}" ]]; then
  if [[ -f "${HOME}/.config/BraveSoftware/Brave-Browser/Default/Cookies" ]]; then
    export GRAVITY_COOKIES_BROWSER=brave
  elif [[ -f "${HOME}/.config/chromium/Default/Cookies" ]]; then
    export GRAVITY_COOKIES_BROWSER=chromium
  elif [[ -f "${HOME}/.config/google-chrome/Default/Cookies" ]]; then
    export GRAVITY_COOKIES_BROWSER=chrome
  elif [[ -f "${HOME}/.config/microsoft-edge/Default/Cookies" ]]; then
    export GRAVITY_COOKIES_BROWSER=edge
  elif [[ -f "${HOME}/.config/opera/Default/Cookies" || -f "${HOME}/.config/opera-stable/Default/Cookies" ]]; then
    export GRAVITY_COOKIES_BROWSER=opera
  elif [[ -f "${HOME}/.config/vivaldi/Default/Cookies" ]]; then
    export GRAVITY_COOKIES_BROWSER=vivaldi
  else
    export GRAVITY_COOKIES_BROWSER=firefox
  fi
fi

mkdir -p "${HOME}/.config/gravity-desktop"
[[ -f "${HOME}/.config/gravity-desktop/env" ]] && source "${HOME}/.config/gravity-desktop/env"

# Cookie decrypt: apt python has secretstorage; yt-dlp-run uses .venv (must too).
# Never test PATH python3 (miniforge) — that false-warned "secretstorage missing".
if [[ ! -f "${HOME}/.config/gravity-desktop/cookies.txt" ]]; then
  if ! /usr/bin/python3 -c "import secretstorage" 2>/dev/null; then
    echo "WARN: apt python3-secretstorage missing — sudo apt install python3-secretstorage" >&2
  fi
  if [[ -x "${APP_DIR}/.venv/bin/python3" ]] \
     && ! "${APP_DIR}/.venv/bin/python3" -c "import secretstorage" 2>/dev/null; then
    echo "WARN: .venv missing secretstorage (yt-dlp-run cookies-from-browser)." >&2
    echo "  Fix: ${APP_DIR}/.venv/bin/pip install secretstorage" >&2
  fi
fi

export PATH="${HOME}/.deno/bin:/usr/bin:/usr/local/bin:${HOME}/.local/bin:${PATH}"

javac GravityDesktop.java
exec java GravityDesktop "$@"