#!/usr/bin/env bash
set -euo pipefail
APP_DIR="$(cd "$(dirname "$0")" && pwd)"
HOME_DIR="${HOME}"
PASS=0
FAIL=0
WARN=0

ok()   { echo "  OK   $1"; PASS=$((PASS+1)); }
bad()  { echo "  FAIL $1"; FAIL=$((FAIL+1)); }
warn() { echo "  WARN $1"; WARN=$((WARN+1)); }

check_exec() {
  local label="$1" path="$2"
  if [[ -x "$path" ]]; then ok "$label → $path"
  else bad "$label → $path (missing or not executable)"; fi
}

check_file() {
  local label="$1" path="$2"
  if [[ -f "$path" || -L "$path" ]]; then ok "$label → $path"
  else bad "$label → $path (missing)"; fi
}

version_of() {
  local path="$1"
  if [[ -x "$path" ]]; then
    "$path" --version 2>&1 | head -1 || true
  fi
}

echo "GravityDesktop dependency check"
echo "App dir: $APP_DIR"
echo

# --- run.sh env (same as launch) ---
export GRAVITY_DESKTOP_HOME="$APP_DIR"
if [[ -x "$APP_DIR/yt-dlp" ]]; then
  export YTDLP_PATH="$APP_DIR/yt-dlp"
else
  export YTDLP_PATH="${YTDLP_PATH:-/usr/bin/yt-dlp}"
fi
export FFMPEG_PATH="${FFMPEG_PATH:-/usr/bin/ffmpeg}"
export PATH="${HOME_DIR}/.deno/bin:/usr/bin:/usr/local/bin:${HOME_DIR}/.local/bin:${PATH}"

echo "Bundled downloads"
if [[ -x "$APP_DIR/yt-dlp" ]]; then
  ok "yt-dlp binary → $APP_DIR/yt-dlp ($(version_of "$APP_DIR/yt-dlp"))"
  if "$APP_DIR/yt-dlp" --help 2>&1 | grep -q -- '--js-runtimes'; then
    ok "bundled yt-dlp supports --js-runtimes"
  else
    bad "bundled yt-dlp missing --js-runtimes support"
  fi
else
  bad "yt-dlp binary → $APP_DIR/yt-dlp (run: curl -fsSL -o $APP_DIR/yt-dlp https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp && chmod +x $APP_DIR/yt-dlp)"
fi
echo

echo "System binaries (required)"
check_exec "java"    "$(command -v java || echo /usr/bin/java)"
check_exec "javac"   "$(command -v javac || echo /usr/bin/javac)"
check_exec "ffmpeg"  "$FFMPEG_PATH"
check_exec "python3" "$(command -v python3 || echo /usr/bin/python3)"
check_exec "deno"    "${HOME_DIR}/.deno/bin/deno"
echo

echo "System binaries (optional)"
if command -v node >/dev/null; then ok "node → $(command -v node) ($(node --version 2>&1))"
else warn "node → not installed (deno is primary JS runtime)"; fi
if command -v curl >/dev/null; then ok "curl → $(command -v curl)"
else warn "curl → not installed (needed for Deno install button)"; fi
if command -v notify-send >/dev/null; then ok "notify-send → $(command -v notify-send)"
else warn "notify-send → not installed (nls-video desktop notifications only)"; fi
if command -v ffplay >/dev/null; then ok "ffplay → $(command -v ffplay)"
else warn "ffplay → not installed (preview ▶ disabled)"; fi
if command -v ffprobe >/dev/null; then ok "ffprobe → $(command -v ffprobe)"
else warn "ffprobe → not installed (catalog duration/vcodec empty)"; fi
echo

echo "Network utilities (overlay / VPN)"
if command -v tor >/dev/null && ss -tln 2>/dev/null | grep -q ':9050'; then
  ok "tor SOCKS → 127.0.0.1:9050 (Gravity overlay Tor onion; not Brave)"
elif command -v tor >/dev/null; then
  warn "tor installed but :9050 not listening (systemctl start tor)"
else
  warn "tor → not installed (sudo apt install tor) — overlay Tor onion needs system client"
fi
if [[ -x /usr/local/Astrill/astrill ]]; then
  ok "Astrill → /usr/local/Astrill/astrill (Stealth tun0; archive ~/Downloads/NLS-Astrill)"
else
  warn "Astrill → /usr/local/Astrill/astrill missing"
fi
if ip -br link 2>/dev/null | grep -qi tun0; then
  ok "Astrill tun0 up ($(ip -4 -o addr show tun0 2>/dev/null | awk '{print $4}'))"
else
  warn "Astrill tun0 down (launch Astrill, StealthVPN ON)"
fi
if ip -br link 2>/dev/null | grep -qi surfshark; then
  warn "Surfshark iface up — do not stack with Astrill"
fi
echo

echo "Version notes"
SYS_YTDLP="/usr/bin/yt-dlp"
if [[ -x "$SYS_YTDLP" ]]; then
  SYS_VER=$("$SYS_YTDLP" --version 2>&1 | head -1)
  if [[ "$YTDLP_PATH" == "$APP_DIR/yt-dlp" ]]; then
    ok "using bundled yt-dlp (system apt copy is stale: $SYS_VER)"
  elif "$SYS_YTDLP" --help 2>&1 | grep -q -- '--js-runtimes'; then
    ok "system yt-dlp OK ($SYS_VER)"
  else
    warn "system yt-dlp is old ($SYS_VER) — missing --js-runtimes; use bundled $APP_DIR/yt-dlp"
  fi
fi
echo

echo "ffmpeg codecs (H.264 rotate)"
FFMPEG_ENCODERS=$(ffmpeg -hide_banner -encoders 2>/dev/null || true)
if grep -q libx264 <<<"$FFMPEG_ENCODERS"; then ok "libx264 encoder available"
else bad "libx264 encoder missing"; fi
if grep -q ' A....D aac' <<<"$FFMPEG_ENCODERS"; then ok "aac encoder available"
else warn "aac encoder missing (audio re-encode on rotate may fail)"; fi
echo

echo "Python packages (nls_video_pipe / hierarchy)"
python3 - <<PY
import importlib.util
from importlib.metadata import version, PackageNotFoundError

def check_pkg(name, required=True, min_note=""):
    try:
        __import__(name.replace("-", "_") if name == "yt-dlp" else name)
        try:
            v = version(name)
        except PackageNotFoundError:
            v = "installed"
        note = f" ({min_note})" if min_note else ""
        print(f"  OK   {name} → {v}{note}")
    except ImportError:
        tag = "FAIL" if required else "WARN"
        print(f"  {tag}  {name} → not installed")

check_pkg("yt_dlp", required=False, min_note="apt 2024.4.9 — GravityDesktop uses bundled CLI, not this")
check_pkg("rich", required=False, min_note="optional TUI polish for nls-video monitor")
print("  OK   sqlite3 → stdlib")
print("  OK   readline → stdlib")
PY
echo

echo "JS runtime (YouTube extraction)"
DENO="${HOME_DIR}/.deno/bin/deno"
if [[ -x "$DENO" ]]; then
  ok "deno → $DENO ($("$DENO" --version 2>&1 | head -1))"
  if [[ -x "$YTDLP_PATH" ]]; then
    if "$YTDLP_PATH" --js-runtimes "deno:$DENO" --simulate -j "https://www.youtube.com/watch?v=jNQXAC9IVRw" >/dev/null 2>&1; then
      ok "yt-dlp + deno YouTube simulate OK"
    else
      bad "yt-dlp + deno YouTube simulate failed"
    fi
  fi
else
  bad "deno → $DENO (install: curl -fsSL https://deno.land/install.sh | sh)"
fi
echo

echo "App files"
check_file "GravityDesktop.java" "$APP_DIR/GravityDesktop.java"
check_file "GravityDesktop.class" "$APP_DIR/GravityDesktop.class"
check_file "hierarchy_interpreter.py" "$APP_DIR/hierarchy_interpreter.py"
check_file "nls_video_pipe.py" "$APP_DIR/nls_video_pipe.py"
check_file "run.sh" "$APP_DIR/run.sh"
echo

echo "Output / pipeline"
if [[ -d "${HOME_DIR}/Downloads" && -w "${HOME_DIR}/Downloads" ]]; then
  ok "Downloads dir writable → ${HOME_DIR}/Downloads"
else
  bad "Downloads dir → ${HOME_DIR}/Downloads (missing or not writable)"
fi
if [[ -L "${HOME_DIR}/.local/bin/nls-video" || -x "${HOME_DIR}/.local/bin/nls-video" ]]; then
  ok "nls-video CLI → ${HOME_DIR}/.local/bin/nls-video"
else
  warn "nls-video CLI not on PATH (optional; for gravity server / worker)"
fi
if ss -tln 2>/dev/null | grep -q ':4242 '; then
  ok "Gravity server listening → 127.0.0.1:4242"
else
  warn "Gravity server not on 127.0.0.1:4242 (UI works; live jobs need nls-video serve/worker)"
fi
echo

echo "Launch env"
ok "GRAVITY_DESKTOP_HOME=$GRAVITY_DESKTOP_HOME"
ok "YTDLP_PATH=$YTDLP_PATH"
ok "FFMPEG_PATH=$FFMPEG_PATH"
echo

echo "Summary: $PASS passed, $WARN warnings, $FAIL failed"
[[ "$FAIL" -eq 0 ]]