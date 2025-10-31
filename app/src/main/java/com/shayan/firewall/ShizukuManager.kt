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
    private var connectivityService: Any? = null
    private var setUidFirewallRuleMethod: Method? = null // API 30+
    private var setFirewallUidRuleMethod: Method? = null // API 28, 29

    /**
     * Gets the IConnectivityManager service and finds the firewall methods using reflection.
     */
    private fun getConnectivityService(): Any? {
        if (connectivityService != null) {
            return connectivityService
        }

        try {
            
            val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("connectivity"))
            
            val stubClass = Class.forName("android.net.IConnectivityManager\$Stub")
            
            val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
            val service = asInterfaceMethod.invoke(null, binder)
            
            if (service == null) {
                Log.e(TAG, "asInterface returned null")
                return null
            }

            
            try {
               
                setUidFirewallRuleMethod = service.javaClass.getMethod("setUidFirewallRule", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            } catch (e: NoSuchMethodException) {
                Log.d(TAG, "setUidFirewallRule not found, trying setFirewallUidRule...")
                try {
                    
                    setFirewallUidRuleMethod = service.javaClass.getMethod("setFirewallUidRule", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                } catch (e2: NoSuchMethodException) {
                    Log.e(TAG, "No valid firewall method found on this device.", e2)
                }
            }

            if (setUidFirewallRuleMethod == null && setFirewallUidRuleMethod == null) {
                Log.e(TAG, "Could not find a valid firewall method.")
                return null
            }
            
            connectivityService = service
            return service

        } catch (e: Exception) {
            Log.e(TAG, "Failed to get IConnectivityManager via reflection", e)
            return null
        }
    }

    /**
     * Applies a firewall rule using the hidden ConnectivityManager API via reflection.
     */
    fun applyRule(uid: Int, isBlocked: Boolean) {
        val manager = getConnectivityService() ?: return
        
        val rule = if (isBlocked) FIREWALL_RULE_DENY else FIREWALL_RULE_ALLOW

        try {
            if (setUidFirewallRuleMethod != null) {
                // API 30+: chain 1 = wifi, chain 2 = mobile
                setUidFirewallRuleMethod?.invoke(manager, 1, uid, rule)
                setUidFirewallRuleMethod?.invoke(manager, 2, uid, rule)
            } else if (setFirewallUidRuleMethod != null) {
                // API 28/29: chain 0 = wifi, chain 1 = mobile
                setFirewallUidRuleMethod?.invoke(manager, 0, uid, rule)
                setFirewallUidRuleMethod?.invoke(manager, 1, uid, rule)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply rule for UID $uid", e)
        }
    }

    fun applyAllRules(context: Context, prefs: FirewallPreferences) {
        val pm = context.packageManager
        try {
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in packages) {
                val isBlocked = prefs.isWifiBlocked(FirewallMode.SHIZUKU, app.packageName)
                applyRule(app.uid, isBlocked)
            }
            Log.d(TAG, "Applied all Shizuku rules.")
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


