package com.nuvio.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the packaged native subtitle AAR against our Media3 fork on a real Android runtime.
 * Reflection crosses the internal composeApp module boundary without publishing a test-only API.
 */
@RunWith(AndroidJUnit4::class)
class PlayerLibassIntegrationTest {
    @Test fun packagedSubtitleEngineBuildsAndReleasesWithForkMedia3() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val exoPlayer = Class.forName("androidx.media3.exoplayer.ExoPlayer")
            val builderType = Class.forName("androidx.media3.exoplayer.ExoPlayer\$Builder")
            val bridge = Class.forName("com.nuvio.app.features.player.PlayerLibassCompatKt")
            val build = bridge.declaredMethods.single { it.name == "buildWithAssSupportCompat\$default" }
            val handler = bridge.declaredMethods.single { it.name == "getAssHandlerCompat" }
            val release = bridge.declaredMethods.single { it.name == "releaseWithAssSupportCompat" }
            repeat(2) {
                val builder = builderType.getConstructor(android.content.Context::class.java).newInstance(context)
                val player = build.invoke(null, builder, context, null, null, null, 14, null)
                try {
                    assertNotNull(handler.invoke(null, player))
                    assertEquals(1, exoPlayer.getMethod("getPlaybackState").invoke(player))
                } finally {
                    release.invoke(null, player)
                }
                assertNull(handler.invoke(null, player))
            }
        }
    }
    @Test fun nativeSubtitleSurfaceAttachesAndReleases() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bridge = Class.forName("com.nuvio.app.features.player.PlayerLibassCompatKt")
        val build = bridge.declaredMethods.single { it.name == "buildWithAssSupportCompat\$default" }
        val handler = bridge.declaredMethods.single { it.name == "getAssHandlerCompat" }
        val release = bridge.declaredMethods.single { it.name == "releaseWithAssSupportCompat" }
        lateinit var player: Any
        lateinit var surface: android.view.SurfaceView
        lateinit var container: android.widget.FrameLayout
        androidx.test.core.app.ActivityScenario.launch(com.nuvio.app.MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val builder = Class.forName("androidx.media3.exoplayer.ExoPlayer\$Builder")
                    .getConstructor(android.content.Context::class.java).newInstance(activity)
                player = build.invoke(null, builder, activity, null, null, null, 14, null)
                val assHandler = handler.invoke(null, player)
                surface = Class.forName("com.nuvio.tv.libass.media.widget.AssSubtitleSurfaceView")
                    .getConstructor(android.content.Context::class.java, assHandler.javaClass)
                    .newInstance(activity, assHandler) as android.view.SurfaceView
                container = android.widget.FrameLayout(activity)
                container.addView(surface, android.widget.FrameLayout.LayoutParams(640, 360))
                activity.addContentView(container, android.view.ViewGroup.LayoutParams(640, 360))
            }
            instrumentation.waitForIdleSync()
            val deadline = android.os.SystemClock.uptimeMillis() + 5_000
            while (!surface.holder.surface.isValid && android.os.SystemClock.uptimeMillis() < deadline) {
                android.os.SystemClock.sleep(25)
            }
            try {
                assertTrue("Subtitle surface was not created", surface.holder.surface.isValid)
                scenario.onActivity {
                    surface.javaClass.getMethod("requestRender", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
                        .invoke(surface, 0L, System.nanoTime())
                }
                instrumentation.waitForIdleSync()
            } finally {
                scenario.onActivity {
                    container.removeView(surface)
                    release.invoke(null, player)
                }
            }
        }
    }

}
