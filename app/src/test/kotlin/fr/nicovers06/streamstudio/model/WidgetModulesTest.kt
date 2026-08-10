package fr.nicovers06.streamstudio.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetModulesTest {
    @Test
    fun `instance limits match the native widget catalog`() {
        assertEquals(1, WidgetModules.of(WidgetType.TIMER).maxInstancesPerScene)
        assertEquals(10, WidgetModules.of(WidgetType.SHAPE).maxInstancesPerScene)
        assertEquals(1, WidgetModules.of(WidgetType.BACKGROUND).maxInstancesPerScene)
        assertEquals(2, WidgetModules.of(WidgetType.TICKER).maxInstancesPerScene)
        assertEquals(1, WidgetModules.of(WidgetType.MEDIA).maxInstancesPerScene)
        assertEquals(Int.MAX_VALUE, WidgetModules.of(WidgetType.ALERT).maxInstancesPerScene)
        assertEquals(1, WidgetModules.of(WidgetType.POLL).maxInstancesPerScene)
        assertEquals(Int.MAX_VALUE, WidgetModules.of(WidgetType.TEXT).maxInstancesPerScene)
    }

    @Test
    fun `timer cannot be added twice while alerts remain addable`() {
        val timer = NativeWidgetComponent.create(WidgetType.TIMER)
        val alerts = List(25) { NativeWidgetComponent.create(WidgetType.ALERT, it + 1) }
        val scene = StreamScene(nativeWidgets = listOf(timer) + alerts)

        assertFalse(WidgetModules.canAdd(scene, WidgetType.TIMER))
        assertTrue(WidgetModules.canAdd(scene, WidgetType.ALERT))
    }

    @Test
    fun `disabled singleton remains present until it is removed`() {
        val scene = StreamScene(screen = ScreenComponent(enabled = false))

        assertEquals(1, WidgetModules.instanceCount(scene, WidgetType.SCREEN))
        assertEquals(0, WidgetModules.activeInstanceCount(scene, WidgetType.SCREEN))
        assertFalse(WidgetModules.canAdd(scene, WidgetType.SCREEN))
    }

    @Test
    fun `removed singleton leaves the layer order and becomes addable`() {
        val screenRef = LayerRef.singleton(WidgetType.SCREEN)
        val scene = StreamScene(
            screenPresent = false,
            screen = ScreenComponent(enabled = false),
        )

        assertFalse(screenRef in scene.normalizedLayerOrder())
        assertEquals(0, WidgetModules.instanceCount(scene, WidgetType.SCREEN))
        assertTrue(WidgetModules.canAdd(scene, WidgetType.SCREEN))

        val readded = scene.copy(screenPresent = true, screen = scene.screen.copy(enabled = true))
        val reordered = WidgetModules.bringToFront(readded.layerOrder, WidgetType.SCREEN, readded)
        assertEquals(screenRef, reordered.first())
    }

    @Test
    fun `missing background layer is normalized behind the scene`() {
        val background = NativeWidgetComponent.create(WidgetType.BACKGROUND)
        val text = NativeWidgetComponent.create(WidgetType.TEXT)
        val scene = StreamScene(
            nativeWidgets = listOf(background, text),
            layerOrder = emptyList(),
        )

        val normalized = scene.normalizedLayerOrder()

        assertEquals(LayerRef.instance(WidgetType.BACKGROUND, background.id), normalized.last())
        assertTrue(normalized.indexOf(LayerRef.instance(WidgetType.TEXT, text.id)) < normalized.lastIndex)
    }

    @Test
    fun `stored background order cannot place it above another widget`() {
        val background = NativeWidgetComponent.create(WidgetType.BACKGROUND)
        val text = NativeWidgetComponent.create(WidgetType.TEXT)
        val backgroundRef = LayerRef.instance(WidgetType.BACKGROUND, background.id)
        val textRef = LayerRef.instance(WidgetType.TEXT, text.id)
        val scene = StreamScene(
            nativeWidgets = listOf(background, text),
            layerOrder = listOf(backgroundRef, textRef),
        )

        val normalized = scene.normalizedLayerOrder()

        assertEquals(textRef, normalized.first())
        assertEquals(backgroundRef, normalized.last())
    }

    @Test
    fun `background cannot be brought to front`() {
        val background = NativeWidgetComponent.create(WidgetType.BACKGROUND)
        val text = NativeWidgetComponent.create(WidgetType.TEXT)
        val backgroundRef = LayerRef.instance(WidgetType.BACKGROUND, background.id)
        val scene = StreamScene(
            nativeWidgets = listOf(background, text),
            layerOrder = listOf(backgroundRef, LayerRef.instance(WidgetType.TEXT, text.id)),
        )

        val reordered = WidgetModules.bringToFront(scene.layerOrder, backgroundRef, scene)

        assertEquals(backgroundRef, reordered.last())
    }

    @Test
    fun `dynamic layer keys round trip`() {
        val ref = LayerRef.instance(WidgetType.TICKER, "ticker-1")
        assertEquals(ref, LayerRef.parse(ref.storageKey()))
        assertEquals(null, LayerRef.parse("TICKER"))
    }

    @Test
    fun `sanitization enforces caps without truncating unlimited widgets`() {
        val shapes = List(12) { NativeWidgetComponent.create(WidgetType.SHAPE, it + 1) }
        val tickers = List(3) { NativeWidgetComponent.create(WidgetType.TICKER, it + 1) }
        val texts = List(15) { NativeWidgetComponent.create(WidgetType.TEXT, it + 1) }

        val sanitized = StreamScene.sanitizeNativeWidgets(shapes + tickers + texts)

        assertEquals(10, sanitized.count { it.type == WidgetType.SHAPE })
        assertEquals(2, sanitized.count { it.type == WidgetType.TICKER })
        assertEquals(15, sanitized.count { it.type == WidgetType.TEXT })
    }

    @Test
    fun `countdown uses its persisted clock anchor`() {
        val timer = NativeWidgetComponent.create(WidgetType.TIMER)
            .copy(timerDurationSeconds = 60L)
            .startTimer(nowEpochMs = 1_000L)

        assertEquals("00:30", timer.formattedTimer(nowEpochMs = 31_000L))
        assertEquals("00:00", timer.formattedTimer(nowEpochMs = 90_000L))
        assertEquals(30_000L, timer.pauseTimer(nowEpochMs = 31_000L).timerBaseElapsedMs)
    }

    @Test
    fun `media playback requires an enabled playing widget with a file`() {
        val media = NativeWidgetComponent.create(WidgetType.MEDIA)
            .copy(mediaFileName = "video.mp4")

        assertTrue(media.isMediaPlaybackActive())
        assertFalse(media.copy(mediaPlaying = false).isMediaPlaybackActive())
        assertFalse(media.copy(enabled = false).isMediaPlaybackActive())
        assertFalse(media.copy(mediaFileName = "").isMediaPlaybackActive())
    }
}
