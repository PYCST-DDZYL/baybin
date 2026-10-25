package com.baybin.phone

import android.Manifest

internal object BluetoothAccess {
    // Retain location for the existing CXR discovery setup; ask only when connecting glasses.
    fun required(sdk: Int): List<String> = buildList {
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (sdk >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    fun canUse(sdk: Int, granted: (String) -> Boolean): Boolean = required(sdk).all(granted)

    fun request(sdk: Int, granted: (String) -> Boolean): List<String> {
        val missing = required(sdk).filterNot(granted)
        if (missing.isEmpty()) return emptyList()
        return missing + if (sdk >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else emptyList()
    }
}
