package com.shayan.firewall

import android.graphics.drawable.Drawable

/**
 * Data class to hold all information about an installed application.
 */
data class AppInfo(
    val appName: String,
    val packageName: String,
    val appIcon: Drawable,
    val isSystemApp: Boolean,
    var isWifiBlocked: Boolean = false,
    var isDataBlocked: Boolean = false,
    var isSelected: Boolean = false // Property for batch selection
)

