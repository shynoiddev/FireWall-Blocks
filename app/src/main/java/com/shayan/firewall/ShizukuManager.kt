package com.shayan.firewall

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

object ShizukuManager {

    private const val TAG = "ShizukuManager"

    // --- Method 1: Iptables (Root Mode, < Android 13) ---
    private const val CHAIN_OUTPUT = "firewall_blocks_output"
    private const val IPTABLES = "iptables"
    private const val IP6TABLES = "ip6tables"
    @Volatile
    private var hasInitializedIptables = false

    // --- Method 2: ConnectivityManager Reflection (ADB Mode, < Android 13) ---
    private const val FIREWALL_RULE_ALLOW = 1
    private const val FIREWALL_RULE_DENY = 2
    @Volatile
    private var connectivityService: Any? = null
    private var setUidFirewallRuleMethod: Method? = null // API 30+
    private var setFirewallUidRuleMethod: Method? = null // API 28, 29

    // --- Method 3: ConnectivityManager Shell (All Modes, Android 13+) ---
    @Volatile
    private var hasInitializedConnectivityShell = false


    // --- Shizuku State Cache ---
    @Volatile
    private var shizukuUidCache: Int = -1

    fun clearUidCache() {
        Log.d(TAG, "Shizuku UID cache cleared.")
        shizukuUidCache = -1
        refreshConnectivityService()
    }

    private fun getShizukuUid(): Int {
        if (shizukuUidCache != -1) {
            return shizukuUidCache
        }
        
        return try {
            val uid = Shizuku.getUid()
            shizukuUidCache = uid
            uid
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get Shizuku UID", e)
            -1 // Error
        }
    }

    private suspend fun executeShellCommand(command: String): Pair<Int, String> {
        return try {
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true

            val process = newProcessMethod.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as Process

            val output = StringBuilder()
            val error = StringBuilder()

            process.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    output.append(line).append("\n")
                }
            }
            process.errorStream.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    error.append(line).append("\n")
                }
            }

            val exitCode = process.waitFor()
            val outputStr = if (output.isNotEmpty()) output.toString().trim() else error.toString().trim()
            
            Pair(exitCode, outputStr)
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku command execution failed for: $command", e)
            Pair(-1, e.message ?: "Shizuku command execution failed")
        }
    }
    
    private suspend fun executeIptablesCommand(command: String): Pair<Int, String> {
        if (getShizukuUid() != 0) {
            Log.w(TAG, "Attempted to run iptables command without root UID.")
            return Pair(-1, "Not in root mode")
        }
        return executeShellCommand(command)
    }

    // --- Method 1: Iptables (Root Mode, < Android 13) ---

    private suspend fun createIptablesChains() {
        Log.d(TAG, "[iptables] Initializing chains...")
        executeIptablesCommand("$IPTABLES -N $CHAIN_OUTPUT 2>/dev/null || true")
        executeIptablesCommand("$IPTABLES -C OUTPUT -j $CHAIN_OUTPUT 2>/dev/null || $IPTABLES -I OUTPUT -j $CHAIN_OUTPUT")
        executeIptablesCommand("$IP6TABLES -N $CHAIN_OUTPUT 2>/dev/null || true")
        executeIptablesCommand("$IP6TABLES -C OUTPUT -j $CHAIN_OUTPUT 2>/dev/null || $IP6TABLES -I OUTPUT -j $CHAIN_OUTPUT")
        hasInitializedIptables = true
        Log.d(TAG, "[iptables] Chains initialized.")
    }

    private suspend fun clearIptablesChains() {
        Log.d(TAG, "[iptables] Clearing chains...")
        executeIptablesCommand("$IPTABLES -F $CHAIN_OUTPUT 2>/dev/null || true")
        executeIptablesCommand("$IP6TABLES -F $CHAIN_OUTPUT 2>/dev/null || true")
    }

    private suspend fun deleteIptablesChains() {
        Log.d(TAG, "[iptables] Deleting chains...")
        executeIptablesCommand("$IPTABLES -D OUTPUT -j $CHAIN_OUTPUT 2>/dev/null || true")
        executeIptablesCommand("$IPTABLES -F $CHAIN_OUTPUT 2>/dev/null || true")
        executeIptablesCommand("$IPTABLES -X $CHAIN_OUTPUT 2>/dev/null || true")
        executeIptablesCommand("$IP6TABLES -D OUTPUT -j $CHAIN_OUTPUT 2>/dev/null || true")
        executeIptablesCommand("$IP6TABLES -F $CHAIN_OUTPUT 2>/dev/null || true")
        executeIptablesCommand("$IP6TABLES -X $CHAIN_OUTPUT 2>/dev/null || true")
        hasInitializedIptables = false
        Log.d(TAG, "[iptables] Chains deleted.")
    }

    private suspend fun applyIptablesRule(uid: Int, isBlocked: Boolean) {
        val rule = "-m owner --uid-owner $uid -j DROP"
        
        executeIptablesCommand("$IPTABLES -D $CHAIN_OUTPUT $rule 2>/dev/null || true")
        executeIptablesCommand("$IP6TABLES -D $CHAIN_OUTPUT $rule 2>/dev/null || true")
        
        if (isBlocked) {
            executeIptablesCommand("$IPTABLES -A $CHAIN_OUTPUT $rule")
            executeIptablesCommand("$IP6TABLES -A $CHAIN_OUTPUT $rule")
        }
        Log.d(TAG, "[iptables] rule: uid=$uid blocked=$isBlocked")
    }

    // --- Method 2: ConnectivityManager Reflection (ADB Mode, < Android 13) ---

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
                Log.e(TAG, "[ConnReflect] asInterface returned null")
                return null
            }

            try {
                setUidFirewallRuleMethod = service.javaClass.getMethod(
                    "setUidFirewallRule",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
            } catch (e: NoSuchMethodException) {
                Log.d(TAG, "[ConnReflect] setUidFirewallRule not found, trying setFirewallUidRule...")
                try {
                    setFirewallUidRuleMethod = service.javaClass.getMethod(
                        "setFirewallUidRule",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                } catch (e2: NoSuchMethodException) {
                    Log.e(TAG, "[ConnReflect] No valid firewall method found on this device.", e2)
                }
            }

            if (setUidFirewallRuleMethod == null && setFirewallUidRuleMethod == null) {
                Log.e(TAG, "[ConnReflect] Could not find a valid firewall method.")
                return null
            }

            connectivityService = service
            Log.d(TAG, "[ConnReflect] Obtained connectivity service via Shizuku")
            return service

        } catch (e: Exception) {
            Log.e(TAG, "[ConnReflect] Failed to get IConnectivityManager via reflection", e)
            return null
        }
    }

    private fun applyConnectivityReflectionRule(uid: Int, isBlocked: Boolean) {
        val rule = if (isBlocked) FIREWALL_RULE_DENY else FIREWALL_RULE_ALLOW

        for (attempt in 1..2) {
            val manager = getConnectivityService() ?: run {
                Log.e(TAG, "[ConnReflect] No connectivity manager available for UID $uid (attempt $attempt).")
                return
            }

            try {
                if (setUidFirewallRuleMethod != null) {
                    setUidFirewallRuleMethod?.invoke(manager, 1, uid, rule)
                    setUidFirewallRuleMethod?.invoke(manager, 2, uid, rule)
                } else if (setFirewallUidRuleMethod != null) {
                    setFirewallUidRuleMethod?.invoke(manager, 0, uid, rule)
                    setFirewallUidRuleMethod?.invoke(manager, 1, uid, rule)
                } else {
                    Log.e(TAG, "[ConnReflect] No firewall method available to invoke for UID $uid.")
                }

                Log.d(TAG, "[ConnReflect] rule: uid=$uid blocked=$isBlocked (attempt $attempt)")
                return // Success
            } catch (e: Exception) {
                Log.e(TAG, "[ConnReflect] Failed to apply rule for UID $uid on attempt $attempt", e)
                if (attempt == 1) {
                    Log.w(TAG, "[ConnReflect] Attempting to refresh service and retry...")
                    refreshConnectivityService() // Clear cache and retry
                } else {
                    Log.e(TAG, "[ConnReflect] Second attempt failed for UID $uid. Aborting.")
                }
            }
        }
    }

    fun refreshConnectivityService() {
        connectivityService = null
        setUidFirewallRuleMethod = null
        setFirewallUidRuleMethod = null
        Log.d(TAG, "[ConnReflect] Service cache cleared.")
    }

    // --- Method 3: ConnectivityManager Shell (All Modes, Android 13+) ---

    private suspend fun enableConnectivityShellChain() {
        Log.d(TAG, "[ConnShell] Enabling chain 3 (OEM_DENY)...")
        executeShellCommand("cmd connectivity set-chain3-enabled true")
        hasInitializedConnectivityShell = true
    }

    private suspend fun disableConnectivityShellChain() {
        Log.d(TAG, "[ConnShell] Disabling chain 3 (OEM_DENY)...")
        executeShellCommand("cmd connectivity set-chain3-enabled false")
        hasInitializedConnectivityShell = false
    }

    private suspend fun applyConnectivityShellRule(packageName: String, isBlocked: Boolean) {
        // This method blocks *all* network access (WiFi and Mobile)
        // 'false' means block, 'true' means allow
        val enabled = !isBlocked
        executeShellCommand("cmd connectivity set-package-networking-enabled $enabled $packageName")
        Log.d(TAG, "[ConnShell] rule: pkg=$packageName blocked=$isBlocked")
    }


    // --- Public Hybrid Methods ---

    suspend fun applyRule(uid: Int, packageName: String, isBlocked: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.d(TAG, "Using API 33+ (ConnShell) method for $packageName")
            if (!hasInitializedConnectivityShell) {
                enableConnectivityShellChain()
            }
            applyConnectivityShellRule(packageName, isBlocked)
        } else if (getShizukuUid() == 0) {
            Log.d(TAG, "Using API < 33 (iptables) method for $packageName")
            if (!hasInitializedIptables) {
                createIptablesChains()
            }
            applyIptablesRule(uid, isBlocked)
        } else {
            Log.d(TAG, "Using API < 33 (ConnReflect) method for $packageName")
            applyConnectivityReflectionRule(uid, isBlocked)
        }
    }

    suspend fun applyAllRules(context: Context, prefs: FirewallPreferences) {
        val pm = context.packageManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.d(TAG, "Applying all rules via API 33+ (ConnShell)...")
            enableConnectivityShellChain()
            try {
                val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                for (app in packages) {
                    val isBlocked = prefs.isWifiBlocked(FirewallMode.SHIZUKU, app.packageName)
                    
                    // 'false' means block, 'true' means allow
                    applyConnectivityShellRule(app.packageName, isBlocked)
                }
                Log.d(TAG, "Applied all ConnShell rules.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply all ConnShell rules", e)
            }
        } else if (getShizukuUid() == 0) {
            Log.d(TAG, "Applying all rules via API < 33 (iptables)...")
            if (!hasInitializedIptables) {
                createIptablesChains()
            }
            clearIptablesChains() // Start fresh
            try {
                val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                for (app in packages) {
                    val isBlocked = prefs.isWifiBlocked(FirewallMode.SHIZUKU, app.packageName)
                    if (isBlocked) {
                        applyIptablesRule(app.uid, true)
                    }
                }
                Log.d(TAG, "Applied all iptables rules.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply all iptables rules", e)
            }
        } else {
            Log.d(TAG, "Applying all rules via API < 33 (ConnReflect)...")
            try {
                val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                for (app in packages) {
                    val isBlocked = prefs.isWifiBlocked(FirewallMode.SHIZUKU, app.packageName)
                    applyConnectivityReflectionRule(app.uid, isBlocked)
                }
                Log.d(TAG, "Applied all ConnReflect rules.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply all ConnReflect rules", e)
            }
        }
    }

    suspend fun removeAllRules(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.d(TAG, "Removing all rules via API 33+ (ConnShell)...")
            if (hasInitializedConnectivityShell) {
                disableConnectivityShellChain()
            }
        } else if (getShizukuUid() == 0) {
            Log.d(TAG, "Removing all rules via API < 33 (iptables)...")
            if (hasInitializedIptables) {
                deleteIptablesChains()
            }
        } else {
            Log.d(TAG, "Removing all rules via API < 33 (ConnReflect)...")
            val pm = context.packageManager
            try {
                val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                for (app in packages) {
                    applyConnectivityReflectionRule(app.uid, isBlocked = false)
                }
                Log.d(TAG, "Removed all ConnReflect rules.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove all ConnReflect rules", e)
            }
        }
    }
}


