package com.baybin.phone

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object Net {
    /** True when the phone has a network that claims internet access. */
    fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
