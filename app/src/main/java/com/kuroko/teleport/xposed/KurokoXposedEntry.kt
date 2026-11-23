package com.kuroko.teleport.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LSPosed entry point for Kuroko Teleport.
 *
 * Strictly system-side ONLY: hooks only `android` (system_server) and `com.android.phone`.
 * Target user applications are NEVER injected or hooked.
 */
class KurokoXposedEntry : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "Kuroko-Entry"
        private const val PKG_ANDROID = "android"
        private const val PKG_PHONE = "com.android.phone"

        private val systemServerInitialized = AtomicBoolean(false)
        private val phoneProcessInitialized = AtomicBoolean(false)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        val cl = lpparam.classLoader ?: return

        when (pkg) {
            PKG_ANDROID -> {
                if (lpparam.processName != null && lpparam.processName != PKG_ANDROID) {
                    return
                }
                if (!systemServerInitialized.compareAndSet(false, true)) {
                    return
                }
                XposedBridge.log("$TAG: Initializing Kuroko Teleport system_server hooks")
                safeInit("LocationHook") { LocationHook.initSystemServer(cl) }
                safeInit("WifiConnectivityHook") { WifiConnectivityHook.initSystemServer(cl) }
                safeInit("TelephonyHook(system_server)") { TelephonyHook.initSystemServer(cl) }
                safeInit("HyperOsHook") { HyperOsHook.initSystemServer(cl) }
            }

            PKG_PHONE -> {
                if (!phoneProcessInitialized.compareAndSet(false, true)) {
                    return
                }
                XposedBridge.log("$TAG: Initializing Kuroko Teleport com.android.phone hooks")
                safeInit("TelephonyHook(com.android.phone)") { TelephonyHook.initPhoneProcess(cl) }
            }

            else -> {
                // Zero target app hooking by design.
                return
            }
        }
    }

    private inline fun safeInit(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: $name initialization failed (non-fatal): $t")
        }
    }
}
