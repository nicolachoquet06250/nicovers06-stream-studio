package fr.nicovers06.streamstudio.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCapabilitiesTest {
    @Test
    fun `android 7 utilise uniquement les capacites disponibles en api 24`() {
        val apiLevel = 24

        assertTrue(AndroidCapabilities.isSupported(apiLevel))
        assertFalse(AndroidCapabilities.supportsNotificationChannels(apiLevel))
        assertFalse(AndroidCapabilities.supportsModernCanvasClear(apiLevel))
        assertFalse(AndroidCapabilities.supportsMediaProjectionForegroundServiceType(apiLevel))
        assertFalse(AndroidCapabilities.supportsCameraAndMicrophoneForegroundServiceTypes(apiLevel))
        assertFalse(AndroidCapabilities.requiresNotificationPermission(apiLevel))
        assertFalse(AndroidCapabilities.supportsSingleAppScreenSharing(apiLevel))
    }

    @Test
    fun `les versions recentes conservent toutes leurs capacites`() {
        val apiLevel = 36

        assertTrue(AndroidCapabilities.isSupported(apiLevel))
        assertTrue(AndroidCapabilities.supportsNotificationChannels(apiLevel))
        assertTrue(AndroidCapabilities.supportsModernCanvasClear(apiLevel))
        assertTrue(AndroidCapabilities.supportsMediaProjectionForegroundServiceType(apiLevel))
        assertTrue(AndroidCapabilities.supportsCameraAndMicrophoneForegroundServiceTypes(apiLevel))
        assertTrue(AndroidCapabilities.requiresNotificationPermission(apiLevel))
        assertTrue(AndroidCapabilities.supportsSingleAppScreenSharing(apiLevel))
    }

    @Test
    fun `chaque capacite moderne est activee uniquement a partir de sa version`() {
        assertFalse(AndroidCapabilities.supportsNotificationChannels(25))
        assertTrue(AndroidCapabilities.supportsNotificationChannels(26))
        assertFalse(AndroidCapabilities.supportsModernCanvasClear(28))
        assertTrue(AndroidCapabilities.supportsModernCanvasClear(29))
        assertFalse(AndroidCapabilities.supportsMediaProjectionForegroundServiceType(28))
        assertTrue(AndroidCapabilities.supportsMediaProjectionForegroundServiceType(29))
        assertFalse(AndroidCapabilities.supportsCameraAndMicrophoneForegroundServiceTypes(29))
        assertTrue(AndroidCapabilities.supportsCameraAndMicrophoneForegroundServiceTypes(30))
        assertFalse(AndroidCapabilities.requiresNotificationPermission(32))
        assertTrue(AndroidCapabilities.requiresNotificationPermission(33))
        assertFalse(AndroidCapabilities.supportsSingleAppScreenSharing(33))
        assertTrue(AndroidCapabilities.supportsSingleAppScreenSharing(34))
    }

    @Test
    fun `une version anterieure a android 7 reste non supportee`() {
        assertFalse(AndroidCapabilities.isSupported(23))
    }
}
