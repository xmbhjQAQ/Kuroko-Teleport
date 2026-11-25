#!/system/bin/sh
MODDIR=${0%/*}
STATE_DIR="/data/adb/kuroko"
GUARD_FILE="$STATE_DIR/boot_guard"
SOCK_PATH="/dev/kuroko_root.sock"
DAEMON="$MODDIR/bin/kuroko_daemon"

# Ensure state directory exists with root-only permissions
mkdir -p "$STATE_DIR"
chmod 0700 "$STATE_DIR"
chown 0:0 "$STATE_DIR" 2>/dev/null

# Clear stale runtime GMS status from previous boot (preserve user's gms_disabled preference)
rm -f "$STATE_DIR/gms_status"

# Read and increment anti-bootloop boot_guard counter
COUNT=0
if [ -f "$GUARD_FILE" ]; then
    RAW=$(cat "$GUARD_FILE" 2>/dev/null | tr -dc '0-9')
    if [ -n "$RAW" ]; then
        COUNT=$RAW
    fi
fi
COUNT=$((COUNT + 1))
printf '%s\n' "$COUNT" > "$GUARD_FILE"
chmod 0600 "$GUARD_FILE"

# Abort daemon startup if boot_guard tripped (2+ consecutive unfinished boots)
if [ "$COUNT" -ge 2 ]; then
    exit 0
fi

# Start kuroko_daemon in daemonized mode and wait up to 2s for companion socket
if [ -f "$DAEMON" ]; then
    chmod 0755 "$DAEMON"
    if ! pidof kuroko_daemon >/dev/null 2>&1; then
        "$DAEMON" --daemonize
    fi
    i=0
    while [ "$i" -lt 20 ]; do
        if [ -S "$SOCK_PATH" ]; then
            break
        fi
        sleep 0.1 2>/dev/null || sleep 1
        i=$((i + 1))
    done
fi
