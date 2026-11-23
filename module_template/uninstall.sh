#!/system/bin/sh
# Kuroko Teleport uninstall script — Policy A2: intentionally preserve com.kuroko.teleport and user saved routes/settings (NO pm uninstall).

# Stop kuroko_daemon gracefully, then force-kill if needed
PIDS=$(pidof kuroko_daemon 2>/dev/null)
if [ -n "$PIDS" ]; then
    kill -TERM $PIDS 2>/dev/null
    sleep 0.5 2>/dev/null || sleep 1
    PIDS=$(pidof kuroko_daemon 2>/dev/null)
    if [ -n "$PIDS" ]; then
        kill -KILL $PIDS 2>/dev/null
    fi
fi

# Remove companion Unix socket and persistent state directory (including any pending_apk cache)
rm -f /dev/kuroko_root.sock
rm -rf /data/adb/kuroko
