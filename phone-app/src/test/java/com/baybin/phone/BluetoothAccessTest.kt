package com.baybin.phone

import android.Manifest
import org.junit.Assert.*
import org.junit.Test

class BluetoothAccessTest {
    @Test fun legacyAndroidUsesLocationAndModernAndroidAlsoRequestsNearbyDevices() {
        val legacy = setOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        assertEquals(legacy, BluetoothAccess.required(30).toSet())
        assertEquals(legacy + setOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            BluetoothAccess.required(31).toSet())
    }

    @Test fun denyingOptionalNotificationsDoesNotBlockConnectionOrTriggerAnotherPrompt() {
        val granted: (String) -> Boolean = { it != Manifest.permission.POST_NOTIFICATIONS }
        assertTrue(BluetoothAccess.canUse(34, granted))
        assertTrue(BluetoothAccess.request(34, granted).isEmpty())
    }

    @Test fun missingScanOrPreciseLocationCannotBeMistakenForCompletePermission() {
        for (missing in listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)) {
            val granted: (String) -> Boolean = { it != missing }
            assertFalse(BluetoothAccess.canUse(34, granted))
            assertEquals(listOf(missing), BluetoothAccess.request(34, granted))
        }
    }

    @Test fun firstConnectionRequestsNotificationsOnlyOnSupportedAndroidVersions() {
        assertFalse(Manifest.permission.POST_NOTIFICATIONS in BluetoothAccess.request(32) { false })
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in BluetoothAccess.request(33) { false })
    }

    @Test fun actionsAndItemNamesHaveChineseAndEnglishText() {
        for (lang in listOf("zh", "en")) {
            for (key in listOf("working_hint", "retry_gallery", "cancel_gallery", "gallery_cancelled",
                "bluetooth_off", "bluetooth_unavailable", "permissions_help", "open_settings")) {
                assertTrue(Lang.s(lang, key).isNotBlank())
            }
        }
        assertEquals("塑料杯", Lang.itemName("plastic_cup", "Plastic cup", "zh"))
        assertEquals("Plastic cup", Lang.itemName("plastic_cup", "Plastic cup", "en"))
        assertEquals("New item", Lang.itemName("new_item", "New item", "zh"))
    }
}
