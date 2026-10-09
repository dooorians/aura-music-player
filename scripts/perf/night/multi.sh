#!/usr/bin/env bash
# Full A/B validation, balanced order: each step installs a build, compiles it like the Play Store would (speed),
# runs the same warm-up tour, then the whole suite.  Usage: abba.sh TAG apkA apkB    -> labels TAG_A1 TAG_B1 TAG_B2 TAG_A2
source "$(dirname "$0")/lib.sh"
TAG="$1"; shift
warmup() {
  CUR=warmup; go_home; for i in 1 2; do sg 540 850 540 350 150 0.6; sg 540 350 540 850 150 0.6; done
  go_library; tapd "Titres" 1.2; for i in 1 2 3; do sg 540 820 540 330 120 0.6; done; tapd "Albums" 1.2; for i in 1 2; do sg 540 820 540 330 120 0.6; done
  pill_tap; tp 540 850 2.2; bk 1.6; sg 540 600 540 200 200 1.4; bk 1.4; player_close
  tapd "Rechercher" 1.6; adb shell input text a; sleep 1; for k in 1 2 3; do has 'text="Tout"' || break; bk 1.2; done
  to_root
}
# Palindromic order over any number of builds given as NAME=apk (e.g. B2=... C1=... D1=...): each build appears twice,
# first in the given order, then in reverse. Journeys come from $JOURNEYS (repeated $REPS times per visit).
ORDER=("$@"); REV=(); for ((i=${#ORDER[@]}-1;i>=0;i--)); do REV+=("${ORDER[$i]}"); done
n=0
for step in "${ORDER[@]}" "${REV[@]}"; do
  n=$((n+1)); name=${step%%=*}; apk=${step#*=}
  awake || exit 3
  echo "$(ts) ${TAG}_${name}#$n install $(md5 -q "$apk")"
  adb install -r "$apk" | tail -1
  adb shell cmd package compile -m speed -f "$PKG" | tail -1
  adb shell am start -n "$ACT" >/dev/null; sleep 6
  warmup
  for r in $(seq 1 ${REPS:-1}); do NOSUFFIX= bash "$HERE/suite.sh" "${TAG}_${name}v$n" ${JOURNEYS:-}; done
done
echo "$(ts) DONE"
