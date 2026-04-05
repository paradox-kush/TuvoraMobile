#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLEW="$ROOT_DIR/gradlew"

ANDROID_APP_ID="com.nuvio.app"
ANDROID_ACTIVITY=".MainActivity"
IOS_PROJECT="$ROOT_DIR/iosApp/iosApp.xcodeproj"
IOS_SCHEME="iosApp"
IOS_DERIVED_DATA_BASE="$ROOT_DIR/build/ios-derived"
IOS_APP_NAME="Nuvio.app"
IOS_BUNDLE_ID="com.nuvio.app.Nuvio"
IOS_PREFERRED_DEVICE_MODEL="iPhone 14 Pro"

usage() {
  cat <<'EOF'
Usage:
  ./scripts/run-mobile.sh android [full|playstore]
  ./scripts/run-mobile.sh ios [s|p] [full|appstore]

Builds the debug app for the selected platform, installs it on all available
Android emulators, a booted iOS simulator, or the configured iOS physical
device, and launches the app.
EOF
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

android_emulator_avds() {
  emulator -list-avds
}

booted_android_emulator_serials() {
  adb devices | awk '$2 == "device" && $1 ~ /^emulator-/ { print $1 }'
}

wait_for_android_emulator() {
  local serial="$1"
  local boot_completed=""

  adb -s "$serial" wait-for-device >/dev/null

  until [[ "$boot_completed" == "1" ]]; do
    boot_completed="$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    sleep 1
  done
}

boot_android_emulator() {
  local avd_name="$1"

  echo "Opening Android emulator $avd_name..." >&2
  emulator "@$avd_name" >/dev/null 2>&1 &
}

first_booted_ios_simulator() {
  xcrun simctl list devices booted | awk -F '[()]' '/Booted/ { print $2; exit }'
}

preferred_ios_device() {
  xcrun xcdevice list --timeout 5 2>/dev/null | python3 -c '
import json
import sys
import os

try:
    devices = json.load(sys.stdin)
except Exception:
    sys.exit(0)

physical = [
    device for device in devices
    if device.get("platform") == "com.apple.platform.iphoneos"
    and not device.get("simulator", False)
    and device.get("available") is True
    and device.get("modelName") == os.environ["IOS_PREFERRED_DEVICE_MODEL"]
]

if physical:
    print(physical[0].get("identifier", ""))
'
}

validate_ios_distribution() {
  local distribution="$1"

  case "$distribution" in
    full|appstore)
      ;;
    *)
      echo "Unknown iOS distribution: $distribution" >&2
      echo "Expected one of: full, appstore" >&2
      exit 1
      ;;
  esac
}

ios_derived_data_path() {
  local target="$1"
  local distribution="$2"
  echo "$IOS_DERIVED_DATA_BASE-$distribution-$target"
}

run_android() {
  local flavor="${1:-full}"

  case "$flavor" in
    full|playstore)
      ;;
    *)
      echo "Unknown Android flavor: $flavor" >&2
      echo "Expected one of: full, playstore" >&2
      exit 1
      ;;
  esac

  require_command adb
  require_command emulator

  local booted_serials=()
  local serial=""
  while IFS= read -r serial; do
    [[ -n "$serial" ]] || continue
    booted_serials+=("$serial")
  done < <(booted_android_emulator_serials)

  if [[ ${#booted_serials[@]} -gt 0 ]]; then
    echo "Using running Android emulators: ${booted_serials[*]}"
  else
  local avds=()
  while IFS= read -r avd_name; do
    [[ -n "$avd_name" ]] || continue
    avds+=("$avd_name")
  done < <(android_emulator_avds)

    if [[ ${#avds[@]} -eq 0 ]]; then
      echo "No Android emulators available." >&2
      echo "Create an AVD first, then rerun: ./scripts/run-mobile.sh android" >&2
      exit 1
    fi

    local avd_name
    for avd_name in "${avds[@]}"; do
      boot_android_emulator "$avd_name"
    done

    echo "Waiting for Android emulators to boot..."
    while IFS= read -r serial; do
      [[ -n "$serial" ]] || continue
      booted_serials+=("$serial")
    done < <(booted_android_emulator_serials)

    while [[ ${#booted_serials[@]} -lt ${#avds[@]} ]]; do
      sleep 2
      booted_serials=()
      while IFS= read -r serial; do
        [[ -n "$serial" ]] || continue
        booted_serials+=("$serial")
      done < <(booted_android_emulator_serials)
    done
  fi

  for serial in "${booted_serials[@]}"; do
    wait_for_android_emulator "$serial"
  done

  local flavor_task_part
  local apk_path
  case "$flavor" in
    full)
      flavor_task_part="Full"
      apk_path="$ROOT_DIR/composeApp/build/outputs/apk/full/debug/composeApp-full-debug.apk"
      ;;
    playstore)
      flavor_task_part="Playstore"
      apk_path="$ROOT_DIR/composeApp/build/outputs/apk/playstore/debug/composeApp-playstore-debug.apk"
      ;;
  esac

  echo "Building Android $flavor debug APK..."
  "$GRADLEW" ":composeApp:assemble${flavor_task_part}Debug"

  if [[ ! -f "$apk_path" ]]; then
    echo "Expected APK not found at: $apk_path" >&2
    exit 1
  fi

  for serial in "${booted_serials[@]}"; do
    echo "Installing on emulator $serial..."
    adb -s "$serial" install -r "$apk_path"

    echo "Launching app on $serial..."
    adb -s "$serial" shell am start -n "$ANDROID_APP_ID/$ANDROID_ACTIVITY"
  done
}

run_ios_simulator() {
  local distribution="${1:-appstore}"

  validate_ios_distribution "$distribution"
  require_command xcodebuild
  require_command xcrun

  local simulator_id
  simulator_id="$(first_booted_ios_simulator)"

  if [[ -z "$simulator_id" ]]; then
    echo "No booted iOS simulator found." >&2
    echo "Boot a simulator first, then rerun: ./scripts/run-mobile.sh ios s" >&2
    exit 1
  fi

  local derived_data_path
  derived_data_path="$(ios_derived_data_path simulator "$distribution")"

  local simulator_app_path
  simulator_app_path="$derived_data_path/Build/Products/Debug-iphonesimulator/$IOS_APP_NAME"

  echo "Building iOS $distribution debug app for simulator $simulator_id..."
  env NUVIO_IOS_DISTRIBUTION="$distribution" xcodebuild \
    -project "$IOS_PROJECT" \
    -scheme "$IOS_SCHEME" \
    -configuration Debug \
    -destination "id=$simulator_id" \
    -derivedDataPath "$derived_data_path" \
    build

  if [[ ! -d "$simulator_app_path" ]]; then
    echo "Expected iOS simulator app not found at: $simulator_app_path" >&2
    exit 1
  fi

  echo "Installing on simulator $simulator_id..."
  xcrun simctl install "$simulator_id" "$simulator_app_path"

  echo "Launching app..."
  xcrun simctl launch "$simulator_id" "$IOS_BUNDLE_ID"
}

run_ios_physical() {
  local distribution="${1:-appstore}"

  validate_ios_distribution "$distribution"
  require_command xcodebuild
  require_command xcrun

  local physical_device_id
  physical_device_id="$(IOS_PREFERRED_DEVICE_MODEL="$IOS_PREFERRED_DEVICE_MODEL" preferred_ios_device)"

  if [[ -n "$physical_device_id" ]]; then
    local derived_data_path
    derived_data_path="$(ios_derived_data_path device "$distribution")"

    local device_app_path
    device_app_path="$derived_data_path/Build/Products/Debug-iphoneos/$IOS_APP_NAME"

    echo "Building iOS $distribution debug app for physical device $physical_device_id..."
    env NUVIO_IOS_DISTRIBUTION="$distribution" xcodebuild \
      -project "$IOS_PROJECT" \
      -scheme "$IOS_SCHEME" \
      -configuration Debug \
      -destination "id=$physical_device_id" \
      -derivedDataPath "$derived_data_path" \
      build

    if [[ ! -d "$device_app_path" ]]; then
      echo "Expected iOS app not found at: $device_app_path" >&2
      exit 1
    fi

    echo "Installing on physical device $physical_device_id..."
    xcrun devicectl device install app --device "$physical_device_id" "$device_app_path"

    echo "Launching app..."
    xcrun devicectl device process launch --device "$physical_device_id" "$IOS_BUNDLE_ID"
    return
  fi

  echo "Preferred iOS device not available: $IOS_PREFERRED_DEVICE_MODEL" >&2
  echo "Connect and unlock that device, then rerun: ./scripts/run-mobile.sh ios p" >&2
  exit 1
}

main() {
  if [[ $# -lt 1 ]]; then
    usage
    exit 1
  fi

  case "$1" in
    android)
      if [[ $# -gt 2 ]]; then
        usage
        exit 1
      fi
      run_android "${2:-full}"
      ;;
    ios)
      if [[ $# -lt 2 || $# -gt 3 ]]; then
        usage
        exit 1
      fi

      local ios_distribution="${3:-appstore}"

      case "$2" in
        s)
          run_ios_simulator "$ios_distribution"
          ;;
        p)
          run_ios_physical "$ios_distribution"
          ;;
        *)
          echo "Unknown iOS target: $2" >&2
          usage
          exit 1
          ;;
      esac
      ;;
    -h|--help|help)
      usage
      ;;
    *)
      echo "Unknown platform: $1" >&2
      usage
      exit 1
      ;;
  esac
}

main "$@"
