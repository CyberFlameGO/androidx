/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.camera.extensions.internal.sessionprocessor

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.media.ImageWriter
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraXThreads
import androidx.camera.extensions.impl.PreviewImageProcessorImpl
import androidx.camera.extensions.impl.ProcessResultImpl
import androidx.camera.testing.Camera2Util
import androidx.camera.testing.CameraUtil
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import java.util.concurrent.Executor
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@SdkSuppress(minSdkVersion = 29) // Extensions supported on API 29+
@RunWith(AndroidJUnit4::class)
class PreviewProcessorTest {
    @get:Rule
    val useCamera = CameraUtil.grantCameraPermissionAndPreTest(
        CameraUtil.PreTestCameraIdList(Camera2Config.defaultConfig())
    )

    private lateinit var surfaceTexture: SurfaceTexture
    private lateinit var previewSurface: Surface
    private lateinit var previewProcessor: PreviewProcessor
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private lateinit var fakePreviewImageProcessorImpl: FakePreviewImageProcessorImpl

    companion object {
        const val CAMERA_ID = "0"
        const val WIDTH = 640
        const val HEIGHT = 480
        const val MAX_IMAGES = 6
    }

    @Before
    fun setUp() {
        Assume.assumeTrue(CameraUtil.deviceHasCamera())

        backgroundThread = HandlerThread(
            CameraXThreads.TAG + "preview_processor_test"
        )
        backgroundThread.start()
        backgroundHandler = Handler(backgroundThread.looper)
        surfaceTexture = SurfaceTexture(0)
        surfaceTexture.setDefaultBufferSize(WIDTH, HEIGHT)
        surfaceTexture.detachFromGLContext()

        previewSurface = Surface(surfaceTexture)
        fakePreviewImageProcessorImpl = FakePreviewImageProcessorImpl()
        previewProcessor = PreviewProcessor(
            fakePreviewImageProcessorImpl, previewSurface, Size(WIDTH, HEIGHT)
        )
    }

    @After
    fun tearDown() {
        if (::backgroundThread.isInitialized) {
            backgroundThread.quitSafely()
        }

        if (::surfaceTexture.isInitialized) {
            surfaceTexture.release()
        }
        if (::previewSurface.isInitialized) {
            previewSurface.release()
        }

        if (::previewProcessor.isInitialized) {
            previewProcessor.close()
        }

        if (::fakePreviewImageProcessorImpl.isInitialized) {
            fakePreviewImageProcessorImpl.close()
        }
    }

    @Test
    fun canOutputToPreview(): Unit = runBlocking {
        val cameraDevice = Camera2Util.openCameraDevice(cameraManager, CAMERA_ID, backgroundHandler)
        val imageReaderYuv = ImageReader.newInstance(
            WIDTH, HEIGHT, ImageFormat.YUV_420_888, MAX_IMAGES
        )
        val session = Camera2Util.openCaptureSession(
            cameraDevice,
            arrayListOf(imageReaderYuv.surface),
            backgroundHandler
        )

        previewProcessor.start()

        imageReaderYuv.setOnImageAvailableListener({
            val image = it.acquireNextImage()
            previewProcessor.notifyImage(createImageReference(image))
        }, backgroundHandler)
        Camera2Util.startRepeating(cameraDevice, session, arrayListOf(imageReaderYuv.surface)) {
            previewProcessor.notifyCaptureResult(it)
        }
        val previewUpdateDeferred = CompletableDeferred<Boolean>()
        surfaceTexture.setOnFrameAvailableListener {
            previewUpdateDeferred.complete(true)
        }

        withTimeout(3000) {
            assertTrue(previewUpdateDeferred.await())
        }
    }

    @Test
    fun canCloseProcessor(): Unit = runBlocking {
        val cameraDevice = Camera2Util.openCameraDevice(cameraManager, CAMERA_ID, backgroundHandler)
        val imageReaderYuv = ImageReader.newInstance(
            WIDTH, HEIGHT, ImageFormat.YUV_420_888, MAX_IMAGES
        )
        val session = Camera2Util.openCaptureSession(
            cameraDevice,
            arrayListOf(imageReaderYuv.surface),
            backgroundHandler
        )

        previewProcessor.start()

        imageReaderYuv.setOnImageAvailableListener({
            val image = it.acquireNextImage()
            previewProcessor.notifyImage(createImageReference(image))
        }, backgroundHandler)

        Camera2Util.startRepeating(cameraDevice, session, arrayListOf(imageReaderYuv.surface)) {
            previewProcessor.notifyCaptureResult(it)
        }
        val previewUpdateDeferred = CompletableDeferred<Boolean>()
        surfaceTexture.setOnFrameAvailableListener {
            previewUpdateDeferred.complete(true)
        }

        withTimeout(3000) {
            previewUpdateDeferred.await()
        }
        previewProcessor.close()
        // close the preview surface to see if closing causes any issues.
        surfaceTexture.release()
        previewSurface.release()

        // Delay a little while to see if the close() causes any issue.
        delay(1000)
    }

    private fun createImageReference(image: Image): ImageReference {
        return object : ImageReference {
            private var refCount = 1
            override fun increment(): Boolean {
                if (refCount <= 0) return false
                refCount++
                return true
            }

            override fun decrement(): Boolean {
                if (refCount <= 0) return false
                refCount--
                if (refCount <= 0) {
                    image.close()
                }
                return true
            }

            override fun get(): Image? {
                return image
            }
        }
    }

    private class FakePreviewImageProcessorImpl : PreviewImageProcessorImpl {
        private var imageWriter: ImageWriter? = null
        override fun process(image: Image?, result: TotalCaptureResult?) {
            val emptyImage = imageWriter!!.dequeueInputImage()
            imageWriter!!.queueInputImage(emptyImage)
        }

        override fun process(
            image: Image?,
            result: TotalCaptureResult?,
            resultCallback: ProcessResultImpl?,
            executor: Executor?
        ) {
            val blankImage = imageWriter!!.dequeueInputImage()
            imageWriter!!.queueInputImage(blankImage)
        }

        override fun onOutputSurface(surface: Surface, imageFormat: Int) {
            imageWriter = ImageWriter.newInstance(surface, 2)
        }

        override fun onResolutionUpdate(size: Size) {
        }

        override fun onImageFormatUpdate(imageFormat: Int) {
        }

        fun close() {
            imageWriter?.close()
        }
    }
}