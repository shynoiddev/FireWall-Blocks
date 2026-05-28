package com.shayan.firewall

import android.graphics.drawable.Drawable

/**
 * Data class to hold all information for an intercepted packet/attempt.
 */
data class MonitoringLog(
    val uid: Int,
    val packageName: String,
    val appName: String,
    val appIcon: Drawable?,
    val isSystemApp: Boolean,
    val isEnabled: Boolean,
    val isUninstalled: Boolean,
    val hasInternetPermission: Boolean,
    val domainOrIp: String,
    val timestamp: String,
    var isWifiBlocked: Boolean = false,
    var isDataBlocked: Boolean = false
)