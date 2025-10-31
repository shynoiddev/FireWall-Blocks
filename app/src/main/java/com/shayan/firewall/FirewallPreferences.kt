package com.shayan.firewall

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

class FirewallPreferences(context: Context) {

    private val shizukuPrefs: SharedPreferences =
        context.getSharedPreferences(FirewallMode.SHIZUKU.key, Context.MODE_PRIVATE)
    
    private val vpnPrefs: SharedPreferences =
        context.getSharedPreferences(FirewallMode.VPN.key, Context.MODE_PRIVATE)
        
    private val defaultPrefs: SharedPreferences =
        context.getSharedPreferences("default_prefs", Context.MODE_PRIVATE)
        
    private val TAG = "FirewallPreferences"

    private fun getPrefs(mode: FirewallMode): SharedPreferences {
        return if (mode == FirewallMode.SHIZUKU) shizukuPrefs else vpnPrefs
    }

    private fun getKey(packageName: String, type: String): String {
        return "${packageName}_${type}"
    }

    // --- Wi-Fi Controls ---

    fun setWifiBlocked(mode: FirewallMode, packageName: String, isBlocked: Boolean) {
        getPrefs(mode).edit().putBoolean(getKey(packageName, "wifi"), isBlocked).commit()
    }

    fun isWifiBlocked(mode: FirewallMode, packageName: String): Boolean {
        return getPrefs(mode).getBoolean(getKey(packageName, "wifi"), false)
    }

    // --- Data Controls ---

    fun setDataBlocked(mode: FirewallMode, packageName: String, isBlocked: Boolean) {
        getPrefs(mode).edit().putBoolean(getKey(packageName, "data"), isBlocked).commit()
    }

    fun isDataBlocked(mode: FirewallMode, packageName: String): Boolean {
        return getPrefs(mode).getBoolean(getKey(packageName, "data"), false)
    }

    // --- Copy Logic ---

    fun copySettings(from: FirewallMode, to: FirewallMode) {
        val fromPrefs = getPrefs(from)
        val toEditor = getPrefs(to).edit()

        toEditor.clear()

        fromPrefs.all.forEach { (key, value) ->
            if (value is Boolean) {
                toEditor.putBoolean(key, value)
            }
        }
        toEditor.commit()
    }
    
    // --- Master Firewall State ---
    
    fun setFirewallEnabled(isEnabled: Boolean) {
        defaultPrefs.edit().putBoolean("is_firewall_enabled", isEnabled).apply()
    }
    
    fun isFirewallEnabled(): Boolean {
        return defaultPrefs.getBoolean("is_firewall_enabled", false)
    }
    fun getBlockedPackagesForNetwork(mode: FirewallMode, isWifi: Boolean): Set<String> {
        val prefs = getPrefs(mode)
        val blockedPackages = mutableSetOf<String>()
        val keyType = if (isWifi) "wifi" else "data"
        
        prefs.all.forEach { (key, value) ->
            // Find keys that are blocked (value == true)
            if (value is Boolean && value) {
                // Check if the key matches the current network type
                if (key.endsWith(keyType)) {
                    val packageName = key.substringBeforeLast('_')
                    if (packageName.isNotEmpty()) {
                        blockedPackages.add(packageName)
                    }
                }
            }
        }
        return blockedPackages
    }
    fun exportAllSettings(): String? {
        return try {
            val masterJson = JSONObject()
            
            val shizukuJson = JSONObject()
            shizukuPrefs.all.forEach { (key, value) ->
                shizukuJson.put(key, value)
            }
            
            val vpnJson = JSONObject()
            vpnPrefs.all.forEach { (key, value) ->
                vpnJson.put(key, value)
            }
            
            masterJson.put(FirewallMode.SHIZUKU.key, shizukuJson)
            masterJson.put(FirewallMode.VPN.key, vpnJson)
            
            masterJson.toString(2) // Indent for readability
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting settings", e)
            null
        }
    }

    /**
     * Imports and replaces all settings from a JSON string.
     */
    fun importAllSettings(jsonString: String): Boolean {
        return try {
            val masterJson = JSONObject(jsonString)

            val shizukuJson = masterJson.getJSONObject(FirewallMode.SHIZUKU.key)
            val shizukuEditor = shizukuPrefs.edit().clear()
            shizukuJson.keys().forEach { key ->
                shizukuEditor.putBoolean(key, shizukuJson.getBoolean(key))
            }
            shizukuEditor.commit()

            val vpnJson = masterJson.getJSONObject(FirewallMode.VPN.key)
            val vpnEditor = vpnPrefs.edit().clear()
            vpnJson.keys().forEach { key ->
                vpnEditor.putBoolean(key, vpnJson.getBoolean(key))
            }
            vpnEditor.commit()
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error importing settings", e)
            false
        }
    }
}


