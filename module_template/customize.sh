#!/system/bin/sh
# Kuroko Teleport KSU2 / Zygisk Next All-in-One Module Installer

read_prop() {
    [ -f "$1" ] || return 0
    grep -m1 "^$2=" "$1" 2>/dev/null | cut -d= -f2- | tr -d '\r\n'
}

detect_raw_locale() {
    if [ -f "/data/adb/kuroko/lang" ]; then
        _ov=$(cat "/data/adb/kuroko/lang" 2>/dev/null | tr -d '\r\n ')
        case "$_ov" in
            ""|auto|AUTO|*[!A-Za-z0-9_-]*) ;;
            *)
                printf '%s' "$_ov"
                return 0
                ;;
        esac
    fi
    for _prop in persist.sys.locale ro.product.locale persist.sys.language ro.product.locale.language; do
        _val=$(getprop "$_prop" 2>/dev/null | tr -d '\r\n ')
        if [ -n "$_val" ]; then
            printf '%s' "$_val"
            return 0
        fi
    done
    if command -v settings >/dev/null 2>&1; then
        _val=$(settings get system system_locales 2>/dev/null | cut -d',' -f1 | tr -d '\r\n ')
        if [ -n "$_val" ] && [ "$_val" != "null" ]; then
            printf '%s' "$_val"
            return 0
        fi
    fi
    printf 'en'
}

# Generic data-driven BCP-47 cascading loader:
#   1. Always load lang/en.sh (base fallback)
#   2. Normalize detected locale ('_' -> '-', split primary/script/region; Hans->CN, Hant->TW)
#   3. Overlay lang/<primary>.sh -> lang/<script-region>.sh -> lang/<primary>-<REGION>.sh if present
load_lang_cascade() {
    if [ -f "$MODPATH/lang/en.sh" ]; then
        . "$MODPATH/lang/en.sh"
    fi

    _raw=$(detect_raw_locale | cut -d',' -f1 | tr '_' '-' | tr -dc 'A-Za-z0-9-')
    [ -z "$_raw" ] && return 0

    _p1=$(printf '%s' "$_raw" | cut -d'-' -f1 | tr 'A-Z' 'a-z')
    _p2=""
    _p3=""
    case "$_raw" in
        *-*-*)
            _p2=$(printf '%s' "$_raw" | cut -d'-' -f2)
            _p3=$(printf '%s' "$_raw" | cut -d'-' -f3)
            ;;
        *-*)
            _p2=$(printf '%s' "$_raw" | cut -d'-' -f2)
            ;;
    esac

    LANG_PRIMARY="$_p1"
    LANG_SCRIPT_REGION=""
    LANG_REGION=""

    _p2_low=$(printf '%s' "$_p2" | tr 'A-Z' 'a-z')
    _p2_up=$(printf '%s' "$_p2" | tr 'a-z' 'A-Z')
    _p3_up=$(printf '%s' "$_p3" | tr 'a-z' 'A-Z')

    case "$_p2_low" in
        hant)
            LANG_SCRIPT_REGION="${LANG_PRIMARY}-TW"
            if [ -n "$_p3_up" ] && [ "$_p3_up" != "TW" ]; then
                LANG_REGION="${LANG_PRIMARY}-${_p3_up}"
            else
                LANG_REGION="$LANG_SCRIPT_REGION"
            fi
            ;;
        hans)
            LANG_SCRIPT_REGION="${LANG_PRIMARY}-CN"
            if [ -n "$_p3_up" ] && [ "$_p3_up" != "CN" ]; then
                LANG_REGION="${LANG_PRIMARY}-${_p3_up}"
            else
                LANG_REGION="$LANG_SCRIPT_REGION"
            fi
            ;;
        "")
            LANG_REGION=""
            ;;
        *)
            if [ -n "$_p3_up" ]; then
                LANG_REGION="${LANG_PRIMARY}-${_p3_up}"
            else
                LANG_REGION="${LANG_PRIMARY}-${_p2_up}"
            fi
            ;;
    esac

    if [ -n "$LANG_PRIMARY" ] && [ "$LANG_PRIMARY" != "en" ] && [ -f "$MODPATH/lang/$LANG_PRIMARY.sh" ]; then
        . "$MODPATH/lang/$LANG_PRIMARY.sh"
    fi
    if [ -n "$LANG_SCRIPT_REGION" ] && [ "$LANG_SCRIPT_REGION" != "$LANG_REGION" ] && [ -f "$MODPATH/lang/$LANG_SCRIPT_REGION.sh" ]; then
        . "$MODPATH/lang/$LANG_SCRIPT_REGION.sh"
    fi
    if [ -n "$LANG_REGION" ] && [ "$LANG_REGION" != "en" ] && [ -f "$MODPATH/lang/$LANG_REGION.sh" ]; then
        . "$MODPATH/lang/$LANG_REGION.sh"
    fi
}

load_lang_cascade

if [ "$ARCH" != "arm64" ] && [ "$ABI" != "arm64-v8a" ]; then
    abort "$(printf -- "$MSG_ERR_ABI" "$ARCH" "$ABI")"
fi

# Dynamically localize module.prop description while preserving all other keys & LF line endings
if [ -n "$MOD_DESC" ] && [ -f "$MODPATH/module.prop" ]; then
    _tmp_prop="$MODPATH/module.prop.tmp"
    : > "$_tmp_prop"
    _replaced=0
    while IFS= read -r _line || [ -n "$_line" ]; do
        _line=$(printf '%s' "$_line" | tr -d '\r')
        case "$_line" in
            description=*)
                printf 'description=%s\n' "$MOD_DESC" >> "$_tmp_prop"
                _replaced=1
                ;;
            *)
                printf '%s\n' "$_line" >> "$_tmp_prop"
                ;;
        esac
    done < "$MODPATH/module.prop"
    if [ "$_replaced" -eq 0 ]; then
        printf 'description=%s\n' "$MOD_DESC" >> "$_tmp_prop"
    fi
    mv -f "$_tmp_prop" "$MODPATH/module.prop"
    chmod 0644 "$MODPATH/module.prop"
fi

MOD_VER=$(read_prop "$MODPATH/module.prop" version)
MOD_CODE=$(read_prop "$MODPATH/module.prop" versionCode)
[ -z "$MOD_VER" ] && MOD_VER="unknown"
[ -z "$MOD_CODE" ] && MOD_CODE="0"

API_LEVEL=$(getprop ro.build.version.sdk)
ui_print "$(printf -- "$MSG_INSTALLING" "$MOD_VER" "$MOD_CODE" "$API_LEVEL")"

# Set executable permissions on daemon binary and Zygisk module library
set_perm "$MODPATH/bin/kuroko_daemon" 0 0 0755
set_perm "$MODPATH/zygisk/arm64-v8a.so" 0 0 0755

# Ensure lifecycle scripts carry executable permissions
for script in post-fs-data.sh service.sh uninstall.sh; do
    if [ -f "$MODPATH/$script" ]; then
        set_perm "$MODPATH/$script" 0 0 0755
    fi
done

STATE_DIR="/data/adb/kuroko"
PENDING_DIR="$STATE_DIR/pending_apk"
mkdir -p "$STATE_DIR"
chmod 0700 "$STATE_DIR"
chown 0:0 "$STATE_DIR" 2>/dev/null

# All-in-One bundled Controller/LSPosed APK installation (reclaims module space immediately)
APK_DIR="$MODPATH/apk"
APK_FILE="$APK_DIR/kuroko.apk"
META_FILE="$APK_DIR/apk_meta.prop"

if [ -f "$APK_FILE" ]; then
    APK_PKG=$(read_prop "$META_FILE" packageName)
    APK_VER=$(read_prop "$META_FILE" versionName)
    APK_CODE=$(read_prop "$META_FILE" versionCode)
    APK_SIZE=$(read_prop "$META_FILE" size | tr -dc '0-9')
    APK_SHA256=$(read_prop "$META_FILE" sha256 | tr 'A-F' 'a-f')
    [ -z "$APK_PKG" ] && APK_PKG="com.kuroko.teleport"
    [ -z "$APK_SIZE" ] && APK_SIZE=$(wc -c < "$APK_FILE" 2>/dev/null | tr -dc '0-9')

    ui_print "$(printf -- "$MSG_APK_FOUND" "$APK_PKG" "$APK_VER" "$APK_CODE" "$APK_SIZE")"

    # Verify SHA-256 integrity when sha256sum is available
    if [ -n "$APK_SHA256" ] && command -v sha256sum >/dev/null 2>&1; then
        ACTUAL_SHA=$(sha256sum "$APK_FILE" 2>/dev/null | cut -d' ' -f1 | tr 'A-F' 'a-f')
        if [ -n "$ACTUAL_SHA" ] && [ "$ACTUAL_SHA" != "$APK_SHA256" ]; then
            rm -rf "$APK_DIR"
            abort "$(printf -- "$MSG_APK_SHA_MISMATCH" "$APK_SHA256" "$ACTUAL_SHA")"
        fi
        ui_print "$MSG_APK_SHA_OK"
    fi

    # SELinux-safe stdin streaming install via pm (with cmd package fallback)
    PM_OUT=""
    if command -v pm >/dev/null 2>&1; then
        PM_OUT=$(pm install -r -d -S "$APK_SIZE" < "$APK_FILE" 2>&1)
        if ! printf '%s' "$PM_OUT" | grep -q "Success"; then
            if command -v cmd >/dev/null 2>&1 && ! printf '%s' "$PM_OUT" | grep -Eq "UPDATE_INCOMPATIBLE|SIGNATURES"; then
                PM_OUT2=$(cmd package install -r -d -S "$APK_SIZE" < "$APK_FILE" 2>&1)
                if printf '%s' "$PM_OUT2" | grep -q "Success" || printf '%s' "$PM_OUT2" | grep -Eq "UPDATE_INCOMPATIBLE|SIGNATURES"; then
                    PM_OUT="$PM_OUT2"
                fi
            fi
        fi
    elif command -v cmd >/dev/null 2>&1; then
        PM_OUT=$(cmd package install -r -d -S "$APK_SIZE" < "$APK_FILE" 2>&1)
    else
        PM_OUT="ERR_NO_PM"
    fi

    if printf '%s' "$PM_OUT" | grep -q "Success"; then
        printf 'packageName=%s\nversionName=%s\nversionCode=%s\nsha256=%s\ninstalledBy=customize\n' \
            "$APK_PKG" "$APK_VER" "$APK_CODE" "$APK_SHA256" > "$STATE_DIR/apk_installed.prop"
        chmod 0600 "$STATE_DIR/apk_installed.prop"
        rm -rf "$APK_DIR" "$PENDING_DIR"
        ui_print "$(printf -- "$MSG_APK_INSTALLED" "$APK_VER" "$APK_CODE")"
    elif printf '%s' "$PM_OUT" | grep -Eq "UPDATE_INCOMPATIBLE|SIGNATURES"; then
        # Policy A2: NEVER uninstall existing App so saved routes/settings remain intact
        rm -rf "$APK_DIR" "$PENDING_DIR"
        ui_print "$(printf -- "$MSG_APK_SIG_WARN_1" "$APK_PKG" "$PM_OUT")"
        ui_print "$MSG_APK_SIG_WARN_2"
        ui_print "$MSG_APK_SIG_WARN_3"
    else
        # PMS offline/busy (e.g. recovery flash): stage to /data/adb/kuroko/pending_apk for post-boot install
        rm -rf "$PENDING_DIR"
        mkdir -p "$PENDING_DIR"
        chmod 0700 "$PENDING_DIR"
        cp -f "$APK_FILE" "$PENDING_DIR/kuroko.apk"
        chmod 0600 "$PENDING_DIR/kuroko.apk"
        if [ -f "$META_FILE" ]; then
            cp -f "$META_FILE" "$PENDING_DIR/apk_meta.prop"
            chmod 0600 "$PENDING_DIR/apk_meta.prop"
        fi
        rm -rf "$APK_DIR"
        ui_print "$(printf -- "$MSG_APK_DEFERRED_1" "$PM_OUT")"
        ui_print "$(printf -- "$MSG_APK_DEFERRED_2" "$PENDING_DIR")"
    fi
fi

ui_print "$(printf -- "$MSG_READY" "$MOD_VER")"
ui_print "$MSG_LSPOSED_HINT"
ui_print "$MSG_BOOTGUARD_HINT"
ui_print "$MSG_REBOOT_HINT"

# Keep installed module directory clean after install completes
rm -rf "$MODPATH/lang"
