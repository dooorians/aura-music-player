#!/usr/bin/env bash
# One full measurement pass over the real journeys, on the build currently installed.
# Usage: suite.sh LABEL [journey ...]      (default: all journeys)
# Writes traces/LABEL_<journey>.pftrace, raw/LABEL_<journey>_gfx.txt, raw/LABEL_<journey>_cpu{0,1}.txt,
# raw/LABEL_starts.csv and appends one row per journey to raw/frames.csv.
set -u
source "$(dirname "$0")/lib.sh"
LABEL="$1"; shift
JOURNEYS=("$@")
[ ${#JOURNEYS[@]} -eq 0 ] && JOURNEYS=(home_idle home_scroll lib_titres lib_albums player_oc lyrics queue track_change search lib_idle player_idle bg_fg starts)

measure() { # name, then the gestures as a function name
  CUR="$LABEL/$1"
  play_set "${PLAY:-on}"
  local n=1; while [ -f "$OUT/traces/${LABEL}_$1.pftrace" ] && [ -z "${NOSUFFIX:-}" ]; do n=$((n+1)); LABEL="${LABEL%%#*}#$n"; done
  awake || exit 3
  inapp || reapp
  trace_start; gfx_reset; cpu_snap "$OUT/raw/${LABEL}_$1_cpu0.txt"; local t0; t0=$(date +%s.%N)
  "$2"
  local t1; t1=$(date +%s.%N)
  cpu_snap "$OUT/raw/${LABEL}_$1_cpu1.txt"; gfx_dump "$OUT/raw/${LABEL}_$1_gfx.txt"
  trace_stop "$OUT/traces/${LABEL}_$1.pftrace"
  echo "$1 $(echo "$t1 - $t0" | bc)" >> "$OUT/raw/${LABEL}_durations.txt"
  local row; row=$(python3 "$HERE/frames.py" "$OUT/traces/${LABEL}_$1.pftrace" | tail -1)
  [ -f "$OUT/raw/frames.csv" ] || python3 "$HERE/frames.py" "$OUT/traces/${LABEL}_$1.pftrace" | head -1 | sed 's/^/label,journey,/' > "$OUT/raw/frames.csv"
  echo "$LABEL,$1,$row" >> "$OUT/raw/frames.csv"
  echo "$(ts) $LABEL $1: $row"
}

# --- preconditions (not measured) ---
# Every pass runs on the same track: one with synced lyrics, so the lyrics journey has something to open.
setup_track() {
  to_root; go_library; tapd "Titres" 1.2
  for i in 1 2 3; do sg 540 400 540 800 200 0.5; done
  tapt "-FINAL SIGMA-" 2.0
  play_set on
}
pre_home() { go_home; for i in 1 2 3; do sg 540 400 540 900 200 0.4; done; sleep 1; has 'Bonsoir\|Bonjour\|On écoute' || go_home; }
pre_lib() { go_library; }
play_on() { play_set on; }

# --- journeys (measured) ---
j_home_idle() { sleep 10; }
j_home_scroll() { for r in 1 2; do for i in 1 2 3; do sg 540 850 540 350 150 0.9; done; for i in 1 2; do sg 540 350 540 850 150 0.9; done; done; }
j_lib_titres() { tapd "Titres" 1.4; for i in 1 2 3 4 5; do sg 540 820 540 330 120 0.9; done; for i in 1 2; do sg 540 330 540 820 120 0.9; done; }
j_lib_albums() { tapd "Albums" 1.4; for i in 1 2 3 4 5; do sg 540 820 540 330 120 0.9; done; for i in 1 2; do sg 540 330 540 820 120 0.9; done; }
j_player_oc() { for i in 1 2 3 4 5 6; do pill_tap; player_close; done; }
# Only BACK from the lyrics once they are really open (the player is gone): a BACK from the player closes it, and one more
# from a tab page leaves the app.
j_lyrics() { pill_tap; for i in 1 2 3 4; do tp 540 850 2.4; has 'Ouvrir la file d' && { anomaly "lyrics did not open"; break; }; bk 1.8; done; player_close; }
j_queue() { pill_tap; for i in 1 2 3 4; do sg 540 600 540 200 200 1.6; has 'Fermer la file' || { anomaly "queue did not open"; break; }; bk 1.5; done; player_close; }
j_track_change() { pill_tap; for i in 1 2 3; do tp 803 1081 2.2; done; for i in 1 2 3; do tp 277 1081 2.0; done; player_close; }
j_search() {
  tapd "Rechercher" 1.8
  for c in r e o l; do adb shell input text "$c"; sleep 0.25; done; sleep 1.5
  for i in 1 2 3 4; do adb shell input keyevent 67; sleep 0.2; done
  for c in e m p r e s s; do adb shell input text "$c"; sleep 0.25; done; sleep 1.5
  for k in 1 2 3; do has 'text="Tout"' || break; bk 1.2; done
}
j_lib_idle() { sleep 10; }
j_player_idle() { pill_tap; sleep 10; player_close; }
j_bg_fg() { for i in 1 2 3 4; do adb shell input keyevent 3; sleep 2; adb shell am start -W -n "$ACT" | grep -E 'LaunchState|TotalTime' | tr '\n' ' ' >> "$OUT/raw/${LABEL}_bgfg.txt"; echo >> "$OUT/raw/${LABEL}_bgfg.txt"; sleep 2.5; done; }

starts() {
  : > "$OUT/raw/${LABEL}_starts.csv"
  for i in 1 2 3 4 5 6; do
    awake || exit 3
    r=$(adb shell am start -S -W -n "$ACT" | tr -d '\r' | grep -E 'LaunchState|TotalTime|WaitTime' | tr '\n' ' ')
    echo "cold,$i,$r" >> "$OUT/raw/${LABEL}_starts.csv"; sleep 6
    adb shell input keyevent 3; sleep 2
    r=$(adb shell am start -W -n "$ACT" | tr -d '\r' | grep -E 'LaunchState|TotalTime|WaitTime' | tr '\n' ' ')
    echo "warm,$i,$r" >> "$OUT/raw/${LABEL}_starts.csv"; sleep 3
  done
  echo "$(ts) $LABEL starts done"
}

[ -z "${NOSETUP:-}" ] && setup_track
for j in "${JOURNEYS[@]}"; do
  case $j in
    home_idle) pre_home; measure home_idle j_home_idle ;;
    home_scroll) pre_home; measure home_scroll j_home_scroll ;;
    lib_titres) pre_lib; measure lib_titres j_lib_titres ;;
    lib_albums) pre_lib; measure lib_albums j_lib_albums ;;
    player_oc) pre_lib; play_on; measure player_oc j_player_oc ;;
    lyrics) pre_lib; measure lyrics j_lyrics ;;
    queue) pre_lib; measure queue j_queue ;;
    track_change) pre_lib; measure track_change j_track_change ;;
    search) pre_lib; measure search j_search ;;
    lib_idle) pre_lib; tapd "Titres" 1.4; measure lib_idle j_lib_idle ;;
    player_idle) pre_lib; measure player_idle j_player_idle ;;
    bg_fg) pre_lib; measure bg_fg j_bg_fg ;;
    starts) starts ;;
  esac
done
