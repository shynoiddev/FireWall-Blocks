package com.shayan.firewall

/**
 * Defines the different sorting and filtering modes for the app list.
 * Per the blueprint:
 * - NAME: Sort by name (this is also the "ALL" view)
 * - SYSTEM: Filter to show system apps only
 * - USER: Filter to show user (non-system) apps only
 * - INTERNET_ONLY: Filter to show only apps with INTERNET permission
 */
enum class SortFilterMode {
    NAME,
    SYSTEM,
    USER,
    INTERNET_ONLY
}

