#!/usr/bin/env bash

set -eu

PACKAGE="com.iqh3d.geoexplorer"
ACTIVITY="com.iqh3d.geoexplorer.MainActivity"
SERVICE_PACKAGE="com.wztech.service3d"
ENTRY="30@${ACTIVITY}"
CONFIG_DIR="/sdcard/K3DX/config"
VISIBLE_CONFIG="${CONFIG_DIR}/white_list2.config"
HIDDEN_CONFIG="${CONFIG_DIR}/.white_list2.config"

APK_PATH=""
CONFIG_PATH=""
INSPECT_ONLY=0
ASSUME_YES=0

usage() {
    cat <<'EOF'
Usage: scripts/setup-3dfv-selector.sh [options]

Options:
  --apk PATH          Install or update the APK before configuring 3DFV.
  --config PATH       Use an explicitly selected white_list2.config path.
  --inspect-only      Detect and print the configuration without changing it.
  --yes               Skip the final confirmation prompt.
  -h, --help          Show this help.
EOF
}

fail() {
    printf 'Error: %s\n' "$*" >&2
    exit 1
}

remote_file_exists() {
    adb shell "[ -f '$1' ]" >/dev/null 2>&1
}

remote_contains() {
    adb shell "tr -d '\r' < '$1' | grep -Fqx '$2'" >/dev/null 2>&1
}

remote_has_chrome_entry() {
    adb shell "tr -d '\r' < '$1' | grep -Eiq '^30@.*chrome'" >/dev/null 2>&1
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --apk)
            [ "$#" -ge 2 ] || fail "--apk requires a path"
            APK_PATH="$2"
            shift 2
            ;;
        --config)
            [ "$#" -ge 2 ] || fail "--config requires a path"
            CONFIG_PATH="$2"
            shift 2
            ;;
        --inspect-only)
            INSPECT_ONLY=1
            shift
            ;;
        --yes)
            ASSUME_YES=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            fail "unknown option: $1"
            ;;
    esac
done

command -v adb >/dev/null 2>&1 || fail "adb is not installed or is not on PATH"

DEVICE_COUNT=$(adb devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }')
[ "$DEVICE_COUNT" -eq 1 ] || fail "connect exactly one authorized tablet; found $DEVICE_COUNT"

if [ -n "$APK_PATH" ]; then
    [ -f "$APK_PATH" ] || fail "APK not found: $APK_PATH"
    printf 'Installing APK: %s\n' "$APK_PATH"
    adb install -r "$APK_PATH"
fi

adb shell pm path "$PACKAGE" >/dev/null 2>&1 || fail "$PACKAGE is not installed"
adb shell pm path "$SERVICE_PACKAGE" >/dev/null 2>&1 || fail "$SERVICE_PACKAGE is not installed"

if ! adb shell dumpsys package "$PACKAGE" | tr -d '\r' | grep -E -q "(${ACTIVITY}|${PACKAGE}/\.MainActivity)"; then
    fail "expected Activity was not found in the installed package: $ACTIVITY"
fi

if [ -n "$CONFIG_PATH" ]; then
    case "$CONFIG_PATH" in
        "$VISIBLE_CONFIG"|"$HIDDEN_CONFIG") ;;
        *) fail "--config must be $VISIBLE_CONFIG or $HIDDEN_CONFIG" ;;
    esac
    remote_file_exists "$CONFIG_PATH" || fail "configuration file does not exist: $CONFIG_PATH"
else
    VISIBLE_EXISTS=0
    HIDDEN_EXISTS=0
    remote_file_exists "$VISIBLE_CONFIG" && VISIBLE_EXISTS=1
    remote_file_exists "$HIDDEN_CONFIG" && HIDDEN_EXISTS=1

    if [ "$VISIBLE_EXISTS" -eq 1 ] && [ "$HIDDEN_EXISTS" -eq 0 ]; then
        CONFIG_PATH="$VISIBLE_CONFIG"
    elif [ "$VISIBLE_EXISTS" -eq 0 ] && [ "$HIDDEN_EXISTS" -eq 1 ]; then
        CONFIG_PATH="$HIDDEN_CONFIG"
    elif [ "$VISIBLE_EXISTS" -eq 1 ] && [ "$HIDDEN_EXISTS" -eq 1 ]; then
        VISIBLE_REGISTERED=0
        HIDDEN_REGISTERED=0
        remote_contains "$VISIBLE_CONFIG" "$ENTRY" && VISIBLE_REGISTERED=1
        remote_contains "$HIDDEN_CONFIG" "$ENTRY" && HIDDEN_REGISTERED=1
        VISIBLE_CHROME=0
        HIDDEN_CHROME=0
        remote_has_chrome_entry "$VISIBLE_CONFIG" && VISIBLE_CHROME=1
        remote_has_chrome_entry "$HIDDEN_CONFIG" && HIDDEN_CHROME=1

        if [ "$VISIBLE_REGISTERED" -eq 1 ] && [ "$HIDDEN_REGISTERED" -eq 0 ]; then
            CONFIG_PATH="$VISIBLE_CONFIG"
        elif [ "$VISIBLE_REGISTERED" -eq 0 ] && [ "$HIDDEN_REGISTERED" -eq 1 ]; then
            CONFIG_PATH="$HIDDEN_CONFIG"
        elif [ "$VISIBLE_CHROME" -eq 1 ] && [ "$HIDDEN_CHROME" -eq 0 ]; then
            CONFIG_PATH="$VISIBLE_CONFIG"
        elif [ "$VISIBLE_CHROME" -eq 0 ] && [ "$HIDDEN_CHROME" -eq 1 ]; then
            CONFIG_PATH="$HIDDEN_CONFIG"
        else
            fail "both whitelist files exist and the active one is ambiguous; rerun with --config PATH"
        fi
    else
        fail "no 3DFV whitelist was found under $CONFIG_DIR"
    fi
fi

STAMP=$(date +%Y%m%d-%H%M%S)
BACKUP_PATH="${CONFIG_PATH}.bak.${STAMP}"

printf '\nTablet: %s\n' "$(adb shell getprop ro.product.model | tr -d '\r')"
printf 'Android: %s\n' "$(adb shell getprop ro.build.version.release | tr -d '\r')"
printf 'Display: %s\n' "$(adb shell wm size | tr -d '\r' | tail -1)"
printf 'Detected package: %s\n' "$PACKAGE"
printf 'Detected Activity: %s\n' "$ACTIVITY"
printf 'Proposed entry: %s\n' "$ENTRY"
printf 'Active whitelist: %s\n' "$CONFIG_PATH"
printf 'Exact backup path: %s\n\n' "$BACKUP_PATH"

if [ "$INSPECT_ONLY" -eq 1 ]; then
    if remote_contains "$CONFIG_PATH" "$ENTRY"; then
        printf 'Status: the Activity is already registered.\n'
    else
        printf 'Status: the Activity is not registered on this tablet.\n'
    fi
    exit 0
fi

if [ "$ASSUME_YES" -ne 1 ]; then
    printf 'Continue with this backup and whitelist entry? [y/N] '
    read -r REPLY
    case "$REPLY" in
        y|Y|yes|YES) ;;
        *) printf 'No changes were made.\n'; exit 0 ;;
    esac
fi

adb shell cp "$CONFIG_PATH" "$BACKUP_PATH"
remote_file_exists "$BACKUP_PATH" || fail "backup verification failed: $BACKUP_PATH"

if remote_contains "$CONFIG_PATH" "$ENTRY"; then
    printf 'Whitelist entry already exists; leaving the file unchanged.\n'
else
    adb shell "printf '%s\r\n' '$ENTRY' >> '$CONFIG_PATH'"
    remote_contains "$CONFIG_PATH" "$ENTRY" || fail "whitelist verification failed"
    printf 'Whitelist entry added successfully.\n'
fi

# Reload only the vendor service. Never uninstall it or clear its data.
adb shell am stopservice -n "$SERVICE_PACKAGE/.Service3D" >/dev/null 2>&1 || true
adb shell am startservice -a com.wztech.service -p "$SERVICE_PACKAGE"

adb shell am force-stop "$PACKAGE"
adb shell am start -n "$PACKAGE/.MainActivity"

printf '\n3DFV provisioning completed. Open a video and verify the native edge selector visually.\n'
