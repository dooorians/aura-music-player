#!/usr/bin/env bash
# Quick screening (minutes): each build installed, compiled, a short warm-up, then $JOURNEYS x $REPS on the same track.
# Usage: quick.sh TAG name=apk ...     (env: JOURNEYS="home_scroll" REPS=2)
source "$(dirname "$0")/lib.sh"
TAG="$1"; shift
for step in "$@"; do
  name=${step%%=*}; apk=${step#*=}
  awake || exit 3
  adb install -r "$apk" | tail -1; adb shell cmd package compile -m speed -f "$PKG" | tail -1
  adb shell am start -n "$ACT" >/dev/null; sleep 5
  CUR=quick; to_root; go_library
  for i in 1 2 3; do pill_tap; player_close; done
  for r in $(seq 1 ${REPS:-2}); do bash "$HERE/suite.sh" "${TAG}_${name}" ${JOURNEYS:-home_scroll}; done
done
echo "$(ts) DONE"
