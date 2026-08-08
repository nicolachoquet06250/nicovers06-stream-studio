package fr.nicovers06.streamstudio.platform

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * Centralise les seuils Android utilisés par les fallbacks runtime.
 * Les fonctions restent pures afin de verrouiller le comportement API 24 par tests unitaires.
 */
object AndroidCapabilities {
    const val MIN_SUPPORTED_API = 24

    fun isSupported(apiLevel: Int): Boolean = apiLevel >= MIN_SUPPORTED_API

    @ChecksSdkIntAtLeast(api = 26)
    fun supportsNotificationChannels(): Boolean = supportsNotificationChannels(Build.VERSION.SDK_INT)

    fun supportsNotificationChannels(apiLevel: Int): Boolean = apiLevel >= 26

    @ChecksSdkIntAtLeast(api = 29)
    fun supportsModernCanvasClear(): Boolean = supportsModernCanvasClear(Build.VERSION.SDK_INT)

    fun supportsModernCanvasClear(apiLevel: Int): Boolean = apiLevel >= 29

    @ChecksSdkIntAtLeast(api = 29)
    fun supportsMediaProjectionForegroundServiceType(): Boolean =
        supportsMediaProjectionForegroundServiceType(Build.VERSION.SDK_INT)

    fun supportsMediaProjectionForegroundServiceType(apiLevel: Int): Boolean = apiLevel >= 29

    @ChecksSdkIntAtLeast(api = 30)
    fun supportsCameraAndMicrophoneForegroundServiceTypes(): Boolean =
        supportsCameraAndMicrophoneForegroundServiceTypes(Build.VERSION.SDK_INT)

    fun supportsCameraAndMicrophoneForegroundServiceTypes(apiLevel: Int): Boolean = apiLevel >= 30

    @ChecksSdkIntAtLeast(api = 33)
    fun requiresNotificationPermission(): Boolean = requiresNotificationPermission(Build.VERSION.SDK_INT)

    fun requiresNotificationPermission(apiLevel: Int): Boolean = apiLevel >= 33

    @ChecksSdkIntAtLeast(api = 34)
    fun supportsSingleAppScreenSharing(): Boolean = supportsSingleAppScreenSharing(Build.VERSION.SDK_INT)

    fun supportsSingleAppScreenSharing(apiLevel: Int): Boolean = apiLevel >= 34
}
