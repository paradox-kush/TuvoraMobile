#!/bin/bash
# Drive the Apple TV simulator like `adb shell input keyevent`: D-pad presses, focus, on-screen texts, screenshots.
# Needs the driver running (start it once with `atv-remote.sh start <sim-udid> <derived-data>` after a
# build-for-testing). Examples:
#   atv-remote.sh launch -smokeLocalGuest -smokeTab sports
#   atv-remote.sh right 2 ; atv-remote.sh select ; atv-remote.sh focus ; atv-remote.sh texts
#   atv-remote.sh shot /tmp/hub.png ; atv-remote.sh menu ; atv-remote.sh stop
D=/tmp/tuvora-atv-driver
if [ "$1" = "start" ]; then
  UDID=$2; DD=$3
  [ -n "$UDID" ] && [ -n "$DD" ] || { echo "usage: $0 start <udid> <derived-data>"; exit 2; }
  mkdir -p $D; rm -f $D/cmd $D/out $D/state; touch $D/enabled
  cd "$(dirname "$0")/.." || exit 1
  nohup xcodebuild test-without-building -project TuvoraTV.xcodeproj -scheme TuvoraTV -sdk appletvsimulator \
    -destination "id=$UDID" -derivedDataPath "$DD" -only-testing:TuvoraTVUITests/RemoteDriverTests/testDrive \
    > $D/xcodebuild.log 2>&1 &
  for _ in $(seq 1 180); do [ -f $D/state ] && { echo "driver ready"; exit 0; }; sleep 1; done
  echo "driver did not start; see $D/xcodebuild.log"; exit 1
fi
[ -f $D/enabled ] || { echo "driver not started"; exit 1; }
rm -f $D/out
echo "$*" > $D/cmd.tmp && mv $D/cmd.tmp $D/cmd
for _ in $(seq 1 600); do
  if [ -f $D/out ]; then cat $D/out; echo; [ "$1" = "stop" ] && rm -f $D/enabled; exit 0; fi
  sleep 0.2
done
echo "timeout: no answer from the driver (is it still running? see $D/xcodebuild.log)"; exit 1
