package com.arnav.island.core

import android.os.Build

/**
 * Per-device tuning. Profiles never replace live measurement: the display cutout reported by
 * Android always wins. Profiles only provide a fallback camera position (if a device reports no
 * cutout at all) and small cosmetic defaults.
 */
data class DeviceProfile(
    val id: String,
    val displayName: String,
    val isSamsung: Boolean,
    /** Fallback camera centre (dp from the top) and radius (dp) if no cutout is reported. */
    val fallbackCameraYDp: Float,
    val fallbackCameraRadiusDp: Float,
    val notes: String,
)

object DeviceProfiles {

    /** Galaxy S23+ (SM-S916*): 1080 x 2340, 120 Hz, centred punch-hole. */
    val GalaxyS23Plus = DeviceProfile(
        id = "galaxy-s23-plus",
        displayName = "Galaxy S23+",
        isSamsung = true,
        fallbackCameraYDp = 19f,
        fallbackCameraRadiusDp = 5.4f,
        notes = "Tuned for the centred punch-hole and One UI status bar. Cutout is read from Android at runtime.",
    )

    val GalaxyS23 = GalaxyS23Plus.copy(id = "galaxy-s23", displayName = "Galaxy S23")
    val GalaxyS23Ultra = GalaxyS23Plus.copy(id = "galaxy-s23-ultra", displayName = "Galaxy S23 Ultra", fallbackCameraRadiusDp = 5.0f)

    val GenericSamsung = DeviceProfile(
        id = "samsung",
        displayName = "Samsung Galaxy",
        isSamsung = true,
        fallbackCameraYDp = 18f,
        fallbackCameraRadiusDp = 5.5f,
        notes = "Generic One UI profile.",
    )

    val Generic = DeviceProfile(
        id = "generic",
        displayName = "Android device",
        isSamsung = false,
        fallbackCameraYDp = 18f,
        fallbackCameraRadiusDp = 5.5f,
        notes = "Generic profile; calibrate if your camera is not centred.",
    )

    fun detect(model: String = Build.MODEL, manufacturer: String = Build.MANUFACTURER): DeviceProfile {
        val m = model.uppercase()
        return when {
            m.startsWith("SM-S916") -> GalaxyS23Plus
            m.startsWith("SM-S911") -> GalaxyS23
            m.startsWith("SM-S918") -> GalaxyS23Ultra
            manufacturer.equals("samsung", ignoreCase = true) -> GenericSamsung
            else -> Generic
        }
    }
}
