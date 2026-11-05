#!/usr/bin/env bash
# CPU-sample the app (simpleperf, 4 kHz, call graphs) over $CYCLES open/close cycles of the player on the build installed.
# Usage: sprofile.sh TAG apk mapping.txt   -> raw/samples_TAG.txt (deobfuscated)
source "$(dirname "$0")/lib.sh"
TAG="$1"; APK="$2"; MAP="$3"; CYC=${CYCLES:-6}
SP=~/Library/Android/sdk/ndk/27.0.12077973/simpleperf; export PATH=$PATH:~/Library/Android/sdk/platform-tools
awake || exit 3
adb install -r "$APK" | tail -1; adb shell cmd package compile -m speed -f "$PKG" | tail -1
adb shell am start -n "$ACT" >/dev/null; sleep 5
CUR=sprofile; eval "$(sed -n "/^setup_track()/,/^}/p" "$HERE/suite.sh")"; to_root; setup_track; to_root; go_library
for i in 1 2 3; do pill_tap; player_close; done
adb shell "rm -f /data/local/tmp/perf.data; nohup simpleperf record --app $PKG -f 4000 -g --duration $((CYC*5+6)) -o /data/local/tmp/perf.data > /data/local/tmp/sp.log 2>&1 &"
sleep 3
for i in $(seq 1 $CYC); do pill_tap; player_close; done
sleep $((CYC*5+9-CYC*4-3)); sleep 8
adb pull /data/local/tmp/perf.data "$OUT/raw/perf_$TAG.data" | tail -1
cd "$OUT/raw"; mkdir -p binary_cache
python3 $SP/binary_cache_builder.py -i perf_$TAG.data -lib binary_cache >/dev/null 2>&1
python3 $SP/report_sample.py -i perf_$TAG.data --symfs binary_cache --proguard-mapping-file "$MAP" --show-art-frames -o samples_$TAG.txt 2>&1 | tail -1
ls -la samples_$TAG.txt
