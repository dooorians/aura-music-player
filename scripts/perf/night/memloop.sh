#!/usr/bin/env bash
# Memory over two identical loops (player ×10, Home scrolls, lyrics ×3, queue, search) on the installed build.
# Usage: memloop.sh LABEL
source "$(dirname "$0")/lib.sh"
L="$1"
source <(sed -n '/^setup_track()/,/^}/p' "$HERE/suite.sh")
strip_words(){ ui | grep -o '<node[^>]*text="[^"]\+"[^>]*bounds="\[[0-9]*,8[0-9][0-9]\]' | grep -v '• • •' | grep -q text; }
mem(){ adb shell dumpsys meminfo "$(app_pid)" | grep -E "TOTAL PSS|Java Heap:|Native Heap:|Graphics:" | tr -s ' ' | tr '\n' ' '; echo; }
loop(){
  for i in $(seq 1 10); do pill_tap; player_close; done
  go_home; for r in 1 2 3; do for i in 1 2 3; do sg 540 850 540 350 150 0.5; done; for i in 1 2 3; do sg 540 350 540 850 150 0.5; done; done
  go_library; pill_tap; for k in $(seq 1 30); do strip_words && break; sleep 1; done
  for i in 1 2 3; do tp 540 850 2.2; has 'Ouvrir la file d' || bk 1.8; done
  sg 540 600 540 200 200 1.6; has 'Fermer la file' && bk 1.5; player_close
  tapd "Rechercher" 1.6; adb shell input text "reol"; sleep 2; for k in 1 2 3; do has 'text="Tout"' || break; bk 1.2; done
}
CUR="$L/mem"; setup_track
echo "$L start  $(mem)" >> "$OUT/raw/memloop.txt"
loop; echo "$L loop1  $(mem)" >> "$OUT/raw/memloop.txt"
loop; echo "$L loop2  $(mem)" >> "$OUT/raw/memloop.txt"
