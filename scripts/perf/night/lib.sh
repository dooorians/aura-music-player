#!/usr/bin/env bash
# Shared helpers for the night perf session (2026-10-09). Source this file; do not run it.
# Every gesture is bounded and followed by a focus check: the device is the user's own phone.
export ANDROID_SERIAL="${SERIAL:-192.168.50.130:5555}"
PKG="${PKG:-com.ikko.aura.qa}"
ACT="$PKG/com.ikko.aura.MainActivity"
OUT="${OUT:-/Users/doriansemlali/Documents/Claude/Projects/app/nuit-2026-10-09}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG="$OUT/raw/anomalies.log"
mkdir -p "$OUT/raw" "$OUT/traces"

ts() { date +%H:%M:%S; }
focus() { adb shell dumpsys window | grep -m1 mCurrentFocus; }
inapp() { focus | grep -q "$ACT"; }

# Screen on and unlocked, or give up (never enter the PIN).
awake() {
  local i w l
  for i in $(seq 1 40); do
    w=$(adb shell dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*')
    l=$(adb shell dumpsys trust | grep -m1 -o 'deviceLocked=[01]')
    if [ "$w" = "mWakefulness=Awake" ] && [ "$l" = "deviceLocked=0" ]; then return 0; fi
    [ "$w" != "mWakefulness=Awake" ] && adb shell input keyevent KEYCODE_WAKEUP
    echo "$(ts) waiting: $w $l" >> "$LOG"; sleep 15
  done
  echo "$(ts) DEVICE LOCKED, giving up" >> "$LOG"; return 1
}

anomaly() { echo "$(ts) ANOMALY [$CUR] $1 -> $(focus)" | tee -a "$LOG"; }
# Back into Aura QA without ever sending BACK from outside it.
reapp() { adb shell am start -n "$ACT" >/dev/null 2>&1; sleep 3; }
chk() { adb shell cmd statusbar collapse >/dev/null 2>&1; inapp || { anomaly "$1"; reapp; return 1; }; }

clamp() { local v=$1; [ "$v" -lt "$2" ] && v=$2; [ "$v" -gt "$3" ] && v=$3; echo "$v"; }
# sg x1 y1 x2 y2 [ms] [pause]: swipe kept away from the screen edges
sg() {
  local x1 x2 y1 y2
  x1=$(clamp "$1" 100 980); x2=$(clamp "$3" 100 980); y1=$(clamp "$2" 300 1050); y2=$(clamp "$4" 160 1050)
  adb shell input swipe "$x1" "$y1" "$x2" "$y2" "${5:-250}"; sleep "${6:-0.9}"; chk "swipe $x1,$y1->$x2,$y2"
}
tp() { adb shell input tap "$1" "$2"; sleep "${3:-1.5}"; chk "tap $1,$2"; }
# BACK only while Aura is in front; the caller knows a screen above the tab root is open.
bk() { inapp || { anomaly "back refused"; reapp; return 1; }; adb shell input keyevent 4; sleep "${1:-1.5}"; chk back; }

ui() { adb shell uiautomator dump /sdcard/w.xml >/dev/null 2>&1; adb shell cat /sdcard/w.xml; }
has() { ui | grep -q "$1"; }

# Navigation, from whatever tab page: pager order is Accueil | Bibliothèque.
player_open() { has 'Ouvrir la file d' ; }
to_root() { # close player/lyrics/queue/search/settings until a tab page shows; never BACK from a tab root
  local k d
  for k in 1 2 3 4 5; do
    d=$(ui)
    if echo "$d" | grep -q 'content-desc="Fermer la file"'; then bk 1.5; continue; fi
    if echo "$d" | grep -q 'content-desc="Ouvrir la file d'; then sg 540 500 540 1000 120 1.6; continue; fi
    # A tab page shows the mini player ("Titre suivant") and none of the screens pushed above it.
    # (an album, artist or playlist page also shows the mini player: "Lire tout" / "Mélanger" give it away)
    if echo "$d" | grep -q 'content-desc="Titre suivant"' && ! echo "$d" | grep -q 'text="Tout"\|text="Réglages"\|content-desc="Fermer la file"\|text="Lire tout"\|text="Mélanger"'; then return 0; fi
    bk 1.5
  done
}
go_home() { to_root; sg 200 620 900 620 250 1.2; }
go_library() { to_root; sg 900 620 200 620 250 1.4; }
# A tap with a human finger: pressed for ~100 ms (the mini player starts building the player after the finger has stayed a moment).
pill_tap() { adb shell input swipe 330 1087 330 1087 ${TAP_MS:-100}; sleep 1.8; chk "pill tap"; }
# Taps a control by its content description; a hidden capsule is brought back first with a small upward scroll.
tapd() {
  local k c
  for k in 1 2 3; do
    c=$(ui | grep -o "<node[^>]*content-desc=\"$1\"[^>]*>" | head -1 | grep -o 'bounds="[^"]*"' | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' | awk '{printf "%d %d",($1+$3)/2,($2+$4)/2}')
    if [ -n "$c" ]; then tp $c "${2:-1.4}"; return 0; fi
    sg 540 500 540 640 200 0.8
  done
  anomaly "control not found: $1"; return 1
}
# Taps the first element whose text is exactly $1.
tapt() {
  local c
  c=$(ui | grep -o "<node[^>]*text=\"$1\"[^>]*>" | head -1 | grep -o 'bounds="[^"]*"' | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' | awk '{printf "%d %d",($1+$3)/2,($2+$4)/2}')
  [ -n "$c" ] || { anomaly "text not found: $1"; return 1; }
  tp $c "${2:-1.6}"
}
playing() { adb shell dumpsys media_session | grep -A12 "$PKG" | grep -q "state=PlaybackState {state=PLAYING"; }
play_set() { # on|off, from a tab page: the mini player's own button
  if [ "$1" = on ]; then playing || tapd "Lecture" 1.2; else playing && tapd "Pause" 1.2; fi
  sleep 0.5
}
player_close() { sg 540 500 540 1000 120 1.8; }

# Frame data: gfxinfo summary + a Perfetto trace around a journey.
gfx_reset() { adb shell dumpsys gfxinfo "$PKG" reset >/dev/null; }
gfx_dump() { adb shell dumpsys gfxinfo "$PKG" > "$1"; }
PCFG="${PCFG:-$HERE/perfetto.cfg}"
trace_start() {
  adb shell rm -f /data/misc/perfetto-traces/night.pftrace
  PERF_PID=$(adb shell "cat > /data/local/tmp/night.cfg" < "$PCFG"; adb shell "cat /data/local/tmp/night.cfg | perfetto --background --txt -c - -o /data/misc/perfetto-traces/night.pftrace" | tr -d '\r' | tail -1)
  sleep 1
}
trace_stop() { # $1 = local file
  adb shell kill -TERM "$PERF_PID" 2>/dev/null; sleep 2
  adb pull /data/misc/perfetto-traces/night.pftrace "$1" >/dev/null 2>&1
}

# CPU time (utime+stime, clock ticks) of the app and of each of its threads.
app_pid() { adb shell pidof "$PKG" | tr -d '\r'; }
cpu_snap() { # $1 = file
  local p; p=$(app_pid)
  adb shell "for t in /proc/$p/task/*; do echo \$(cat \$t/comm 2>/dev/null)'|'\$(sed 's/.*) //' \$t/stat 2>/dev/null | cut -d' ' -f12,13); done" > "$1"
}
