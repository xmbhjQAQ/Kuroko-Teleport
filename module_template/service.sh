#!/system/bin/sh
# Kuroko Teleport KSU2 / Zygisk Next Late-Start Service Script
MODDIR=${0%/*}
STATE_DIR="/data/adb/kuroko"
GUARD_FILE="$STATE_DIR/boot_guard"
PENDING_DIR="$STATE_DIR/pending_apk"
LOG_FILE="$STATE_DIR/daemon.log"
DAEMON="$MODDIR/bin/kuroko_daemon"

read_prop() {
    [ -f "$1" ] || return 0
    grep -m1 "^$2=" "$1" 2>/dev/null | cut -d= -f2- | tr -d '\r\n'
}

log_service() {
    mkdir -p "$STATE_DIR"
    chmod 0700 "$STATE_DIR"
    printf '[service.sh] %s\n' "$1" >> "$LOG_FILE" 2>/dev/null
    chmod 0600 "$LOG_FILE" 2>/dev/null
}

# Background loop: wait for boot completion, reset boot_guard, ensure daemon alive, finish deferred APK install
(
    while [ "$(getprop sys.boot_completed)" != "1" ]; do
        sleep 2
    done
    sleep 3

    mkdir -p "$STATE_DIR"
    chmod 0700 "$STATE_DIR"

    GUARD_COUNT=0
    if [ -f "$GUARD_FILE" ]; then
        RAW=$(cat "$GUARD_FILE" 2>/dev/null | tr -dc '0-9')
        if [ -n "$RAW" ]; then
            GUARD_COUNT=$RAW
        fi
    fi

    # Reset boot_guard now that system booted cleanly
    printf '0\n' > "$GUARD_FILE"
    chmod 0600 "$GUARD_FILE"

    # Only ensure kuroko_daemon is running if this boot did not trip boot_guard
    if [ "$GUARD_COUNT" -lt 2 ]; then
        if ! pidof kuroko_daemon >/dev/null 2>&1; then
            if [ -f "$DAEMON" ]; then
                chmod 0755 "$DAEMON"
                "$DAEMON" --daemonize
            fi
        fi
    fi

    # Deferred All-in-One APK installation if customize.sh staged a pending APK
    PENDING_APK="$PENDING_DIR/kuroko.apk"
    PENDING_META="$PENDING_DIR/apk_meta.prop"
    if [ -f "$PENDING_APK" ]; then
        APK_PKG=$(read_prop "$PENDING_META" packageName)
        APK_VER=$(read_prop "$PENDING_META" versionName)
        APK_CODE=$(read_prop "$PENDING_META" versionCode)
        APK_SIZE=$(read_prop "$PENDING_META" size | tr -dc '0-9')
        APK_SHA256=$(read_prop "$PENDING_META" sha256 | tr 'A-F' 'a-f')
        [ -z "$APK_PKG" ] && APK_PKG="com.kuroko.teleport"
        [ -z "$APK_SIZE" ] && APK_SIZE=$(wc -c < "$PENDING_APK" 2>/dev/null | tr -dc '0-9')

        SHA_OK=1
        if [ -n "$APK_SHA256" ] && command -v sha256sum >/dev/null 2>&1; then
            ACTUAL_SHA=$(sha256sum "$PENDING_APK" 2>/dev/null | cut -d' ' -f1 | tr 'A-F' 'a-f')
            if [ -n "$ACTUAL_SHA" ] && [ "$ACTUAL_SHA" != "$APK_SHA256" ]; then
                SHA_OK=0
                log_service "pending_apk sha256 mismatch (expected=$APK_SHA256 got=$ACTUAL_SHA); removing cache"
                rm -rf "$PENDING_DIR"
            fi
        fi

        if [ "$SHA_OK" -eq 1 ]; then
            # Wait up to 30s until PackageManagerService is ready
            i=0
            PMS_READY=0
            while [ "$i" -lt 30 ]; do
                if pm path android >/dev/null 2>&1 || cmd package path android >/dev/null 2>&1; then
                    PMS_READY=1
                    break
                fi
                sleep 1
                i=$((i + 1))
            done

            if [ "$PMS_READY" -eq 1 ]; then
                PM_OUT=""
                if command -v pm >/dev/null 2>&1; then
                    PM_OUT=$(pm install -r -d -S "$APK_SIZE" < "$PENDING_APK" 2>&1)
                    if ! printf '%s' "$PM_OUT" | grep -q "Success"; then
                        if command -v cmd >/dev/null 2>&1 && ! printf '%s' "$PM_OUT" | grep -Eq "UPDATE_INCOMPATIBLE|SIGNATURES"; then
                            PM_OUT2=$(cmd package install -r -d -S "$APK_SIZE" < "$PENDING_APK" 2>&1)
                            if printf '%s' "$PM_OUT2" | grep -q "Success" || printf '%s' "$PM_OUT2" | grep -Eq "UPDATE_INCOMPATIBLE|SIGNATURES"; then
                                PM_OUT="$PM_OUT2"
                            fi
                        fi
                    fi
                elif command -v cmd >/dev/null 2>&1; then
                    PM_OUT=$(cmd package install -r -d -S "$APK_SIZE" < "$PENDING_APK" 2>&1)
                fi

                if printf '%s' "$PM_OUT" | grep -q "Success"; then
                    printf 'packageName=%s\nversionName=%s\nversionCode=%s\nsha256=%s\ninstalledBy=service\n' \
                        "$APK_PKG" "$APK_VER" "$APK_CODE" "$APK_SHA256" > "$STATE_DIR/apk_installed.prop"
                    chmod 0600 "$STATE_DIR/apk_installed.prop"
                    log_service "pending_apk install ok pkg=$APK_PKG ver=$APK_VER($APK_CODE)"
                    rm -rf "$PENDING_DIR"
                elif printf '%s' "$PM_OUT" | grep -Eq "UPDATE_INCOMPATIBLE|SIGNATURES"; then
                    # Policy A2: NEVER uninstall existing App; preserve user data and drop pending cache
                    log_service "pending_apk signature mismatch (preserved existing app): $PM_OUT"
                    rm -rf "$PENDING_DIR"
                else
                    log_service "pending_apk install failed (will retry next boot): $PM_OUT"
                fi
            else
                log_service "pending_apk deferred: PackageManagerService not ready within 30s"
            fi
        fi
    fi
) &
