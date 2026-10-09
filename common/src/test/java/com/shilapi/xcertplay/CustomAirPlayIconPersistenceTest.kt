package com.shilapi.xcertplay

import android.graphics.Bitmap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CustomAirPlayIconPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun cleanUp() {
        AirPlayPersistence.clearCustomAirPlayIcon(context)
    }

    @Test
    fun loadWhenNoIconReturnsNull() {
        assertNull(AirPlayPersistence.loadCustomAirPlayIconFile(context))
        assertNull(AirPlayPersistence.loadCustomAirPlayIconBytes(context))
        assertNull(AirPlayPersistence.loadCustomAirPlayIconBitmap(context))
    }

    @Test
    fun saveAndLoadCustomIconBitmapAndBytes() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val samplePngBytes = stream.toByteArray()

        AirPlayPersistence.saveCustomAirPlayIcon(context, samplePngBytes)

        val file = AirPlayPersistence.loadCustomAirPlayIconFile(context)
        assertNotNull(file)

        val loadedBytes = AirPlayPersistence.loadCustomAirPlayIconBytes(context)
        assertNotNull(loadedBytes)
        assertArrayEquals(samplePngBytes, loadedBytes)

        val loadedBitmap = AirPlayPersistence.loadCustomAirPlayIconBitmap(context)
        assertNotNull(loadedBitmap)
        assertEquals(32, loadedBitmap!!.width)
        assertEquals(32, loadedBitmap.height)

        AirPlayPersistence.clearCustomAirPlayIcon(context)
        assertNull(AirPlayPersistence.loadCustomAirPlayIconFile(context))
        assertNull(AirPlayPersistence.loadCustomAirPlayIconBytes(context))
        assertNull(AirPlayPersistence.loadCustomAirPlayIconBitmap(context))
    }

    @Test
    fun emptyIconFileReturnsNullWithoutCrash() {
        val file = java.io.File(context.filesDir, "airplay-icon.png")
        file.writeBytes(byteArrayOf())
        assertNull(AirPlayPersistence.loadCustomAirPlayIconBytes(context))
        assertNull(AirPlayPersistence.loadCustomAirPlayIconBitmap(context))
    }
}
