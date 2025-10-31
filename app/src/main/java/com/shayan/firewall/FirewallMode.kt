package com.shayan.firewall

/**
 * Defines the two operating modes for the firewall.
 */
enum class FirewallMode(val key: String) {
    SHIZUKU("shizuku_prefs"),
    VPN("vpn_prefs")
}

