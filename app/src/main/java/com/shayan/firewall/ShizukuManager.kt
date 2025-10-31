package com.shayan.firewall

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

object ShizukuManager {

    private const val TAG = "ShizukuManager"
    private const val FIREWALL_RULE_ALLOW = 1
    private const val FIREWALL_RULE_DENY = 2

    // Cache for the service and its methods
    @Volatile
    private var connectivityService: Any? = null
    private var setUidFirewallRuleMethod: Method? = null // API 30+
    private var setFirewallUidRuleMethod: Method? = null // API 28, 29

    private fun getConnectivityService(): Any? {
        if (connectivityService != null) {
            return connectivityService
        }

        try {
            // Use Shizuku to get the system "connectivity" binder
            val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("connectivity"))

            val stubClass = Class.forName("android.net.IConnectivityManager\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
            val service = asInterfaceMethod.invoke(null, binder)

            if (service == null) {
                Log.e(TAG, "asInterface returned null")
                return null
            }

            try {
                // Android 11+ (API 30+)
                setUidFirewallRuleMethod = service.javaClass.getMethod(
                    "setUidFirewallRule",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
            } catch (e: NoSuchMethodException) {
                Log.d(TAG, "setUidFirewallRule not found, trying setFirewallUidRule...")
                try {
                    // Android 9/10 (API 28,29)
                    setFirewallUidRuleMethod = service.javaClass.getMethod(
                        "setFirewallUidRule",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                } catch (e2: NoSuchMethodException) {
                    Log.e(TAG, "No valid firewall method found on this device.", e2)
                }
            }

            if (setUidFirewallRuleMethod == null && setFirewallUidRuleMethod == null) {
                Log.e(TAG, "Could not find a valid firewall method.")
                return null
            }

            connectivityService = service
            Log.d(TAG, "Obtained connectivity service via Shizuku")
            return service

        } catch (e: Exception) {
            Log.e(TAG, "Failed to get IConnectivityManager via reflection", e)
            return null
        }
    }

    /**
     * Force the manager to re-acquire the connectivity service next time
     * (useful after import/export or other operations that might change binder state).
     */
    fun refreshConnectivityService() {
        connectivityService = null
        setUidFirewallRuleMethod = null
        setFirewallUidRuleMethod = null
        Log.d(TAG, "Connectivity service cache cleared (refreshConnectivityService called).")
    }

    /**
     * Applies a firewall rule using the hidden ConnectivityManager API via reflection.
     * This method will retry once if the underlying binder appears stale.
     */
    fun applyRule(uid: Int, isBlocked: Boolean) {
        val rule = if (isBlocked) FIREWALL_RULE_DENY else FIREWALL_RULE_ALLOW

        // Try to apply rule, retry once if we get an exception that could indicate stale binder
        for (attempt in 1..2) {
            val manager = getConnectivityService() ?: run {
                Log.e(TAG, "No connectivity manager available to apply rule for UID $uid (attempt $attempt).")
                return
            }

            try {
                if (setUidFirewallRuleMethod != null) {
                    // API 30+: chain 1 = wifi, chain 2 = mobile
                    setUidFirewallRuleMethod?.invoke(manager, 1, uid, rule)
                    setUidFirewallRuleMethod?.invoke(manager, 2, uid, rule)
                } else if (setFirewallUidRuleMethod != null) {
                    // API 28/29: chain 0 = wifi, chain 1 = mobile
                    setFirewallUidRuleMethod?.invoke(manager, 0, uid, rule)
                    setFirewallUidRuleMethod?.invoke(manager, 1, uid, rule)
                } else {
                    Log.e(TAG, "No firewall method available to invoke for UID $uid.")
                }

                Log.d(TAG, "applyRule: uid=$uid blocked=$isBlocked (attempt $attempt)")
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply rule for UID $uid on attempt $attempt", e)
                // Probably a stale binder/service — clear cache and retry once
                if (attempt == 1) {
                    Log.w(TAG, "Attempting to refresh connectivity service and retry...")
                    refreshConnectivityService()
                    // loop will retry
                } else {
                    Log.e(TAG, "Second attempt failed for UID $uid. Aborting.")
                }
            }
        }
    }

    /**
     * Applies rules for all installed apps by reading preferences for SHIZUKU mode.
     * Logs the packages / UIDs it attempts to change.
     */
    fun applyAllRules(context: Context, prefs: FirewallPreferences) {
        val pm = context.packageManager
        try {
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val appliedList = mutableListOf<String>()

            for (app in packages) {
                try {
                    val isBlocked = prefs.isWifiBlocked(FirewallMode.SHIZUKU, app.packageName)
                    val uid = app.uid
                    applyRule(uid, isBlocked)
                    appliedList.add("${app.packageName}@uid=$uid:${if (isBlocked) "DENY" else "ALLOW"}")
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping ${app.packageName} due to error resolving UID", e)
                }
            }

            Log.d(TAG, "Applied all Shizuku rules. Details: ${appliedList.joinToString(", ")}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply all rules", e)
        }
    }

    fun removeAllRules(context: Context) {
        val pm = context.packageManager
        try {
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in packages) {
                applyRule(app.uid, isBlocked = false) // 'false' means allow
            }
            Log.d(TAG, "Removed all Shizuku rules.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove all rules", e)
        }
    }
}