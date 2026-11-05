#!/usr/bin/env bash
# Lyrics A/B on its own: the lyrics journey needs a real lyric line under the finger, so each pass first plays the same
# track and waits in the player until its strip shows words (not the "• • •" of an instrumental passage).
# Usage: lyrics_ab.sh TAG apkA apkB   (order A B B A)
source "$(dirname "$0")/lib.sh"
TAG="$1"; A="$2"; B="$3"
source <(sed -n '/^setup_track()/,/^}/p' "$HERE/suite.sh")
strip_has_words() { ui | grep -o '<node[^>]*text="[^"]\+"[^>]*bounds="\[[0-9]*,8[0-9][0-9]\]' | grep -v '• • •' | grep -q text; }
for step in A1:$A B1:$B B2:$B A2:$A; do
  name=${step%%:*}; apk=${step#*:}
  awake || exit 3
  adb install -r "$apk" | tail -1; adb shell cmd package compile -m speed -f "$PKG" | tail -1
  adb shell am start -n "$ACT" >/dev/null; sleep 6
  CUR=lyr_setup; setup_track; pill_tap
  for k in $(seq 1 40); do strip_has_words && break; sleep 1; done
  player_close
  NOSETUP=1 bash "$HERE/suite.sh" "${TAG}_$name" lyrics
done
