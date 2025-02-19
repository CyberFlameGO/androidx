/*
 * Copyright 2021 The Android Open Source Project
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

@file:RequiresApi(21) // TODO(b/200306659): Remove and replace with annotation on package-info.java

package androidx.camera.camera2.pipe.compat

import androidx.annotation.RequiresApi
import androidx.camera.camera2.pipe.CameraId
import androidx.camera.camera2.pipe.core.Permissions
import androidx.camera.camera2.pipe.core.Threads
import androidx.camera.camera2.pipe.core.WakeLock
import androidx.camera.camera2.pipe.graph.GraphListener
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal sealed class CameraRequest
internal data class RequestOpen(
    val virtualCamera: VirtualCameraState,
    val share: Boolean = false,
    val graphListener: GraphListener
) : CameraRequest()

internal data class RequestClose(
    val activeCamera: VirtualCameraManager.ActiveCamera
) : CameraRequest()

internal object RequestCloseAll : CameraRequest()

private const val requestQueueDepth = 8

@Suppress("EXPERIMENTAL_API_USAGE")
@RequiresApi(21) // TODO(b/200306659): Remove and replace with annotation on package-info.java
@Singleton
internal class VirtualCameraManager @Inject constructor(
    private val permissions: Permissions,
    private val retryingCameraStateOpener: RetryingCameraStateOpener,
    threads: Threads
) {
    // TODO: Consider rewriting this as a MutableSharedFlow
    private val requestQueue: Channel<CameraRequest> = Channel(requestQueueDepth)
    private val activeCameras: MutableSet<ActiveCamera> = mutableSetOf()

    init {
        threads.globalScope.launch(CoroutineName("CXCP-VirtualCameraManager")) { requestLoop() }
    }

    internal fun open(
        cameraId: CameraId,
        share: Boolean = false,
        graphListener: GraphListener
    ): VirtualCamera {
        val result = VirtualCameraState(cameraId)
        offerChecked(RequestOpen(result, share, graphListener))
        return result
    }

    internal fun closeAll() {
        offerChecked(RequestCloseAll)
    }

    private fun offerChecked(request: CameraRequest) {
        check(requestQueue.trySend(request).isSuccess) {
            "There are more than $requestQueueDepth requests buffered!"
        }
    }

    private suspend fun requestLoop() = coroutineScope {
        val requests = arrayListOf<CameraRequest>()

        while (true) {
            // Stage 1: We have a request, but there is a chance we have received multiple
            //   requests.
            readRequestQueue(requests)

            // Prioritize requests that remove specific cameras from the list of active cameras.
            val closeRequest = requests.firstOrNull { it is RequestClose } as? RequestClose
            if (closeRequest != null) {
                requests.remove(closeRequest)
                if (activeCameras.contains(closeRequest.activeCamera)) {
                    activeCameras.remove(closeRequest.activeCamera)
                }

                launch {
                    closeRequest.activeCamera.close()
                }
                closeRequest.activeCamera.awaitClosed()
                continue
            }

            // If we received a closeAll request, then close every request leading up to it.
            val closeAll = requests.indexOfLast { it is RequestCloseAll }
            if (closeAll >= 0) {
                for (i in 0..closeAll) {
                    val request = requests[0]
                    if (request is RequestOpen) {
                        request.virtualCamera.disconnect()
                    }
                    requests.removeAt(0)
                }

                // Close all active cameras.
                for (activeCamera in activeCameras) {
                    launch {
                        activeCamera.close()
                    }
                }
                for (camera in activeCameras) {
                    camera.awaitClosed()
                }
                activeCameras.clear()
                continue
            }

            // The only way we get to this point is if:
            // A) We received a request
            // B) That request was NOT a Close, or CloseAll request
            val request = requests[0]
            check(request is RequestOpen)

            // Sanity Check: If the camera we are attempting to open is now closed or disconnected,
            // skip this virtual camera request.
            if (request.virtualCamera.value !is CameraStateUnopened) {
                requests.remove(request)
                continue
            }

            // Stage 2: Intermediate requests have been discarded, and we need to evaluate the set
            //   of currently open cameras to the set of desired cameras and close ones that are not
            //   needed. Since close may block, we will re-evaluate the next request after the
            //   desired cameras are closed since new requests may have arrived.
            val cameraIdToOpen = request.virtualCamera.cameraId
            val camerasToClose = if (request.share) {
                emptyList()
            } else {
                activeCameras.filter { it.cameraId != cameraIdToOpen }
            }

            if (camerasToClose.isNotEmpty()) {
                // Shutdown of cameras should always happen first (and suspend until complete)
                activeCameras.removeAll(camerasToClose)
                for (camera in camerasToClose) {
                    // TODO: This should be a dispatcher instead of scope.launch

                    launch {
                        // TODO: Figure out if this should be blocking or not. If we are directly invoking
                        //   close this method could block for 0-1000ms
                        camera.close()
                    }
                }
                for (realCamera in camerasToClose) {
                    realCamera.awaitClosed()
                }
                continue
            }

            // Stage 3: Open or select an active camera device.
            var realCamera = activeCameras.firstOrNull { it.cameraId == cameraIdToOpen }
            if (realCamera == null) {
                realCamera =
                    openCameraWithRetry(cameraIdToOpen, request.graphListener, scope = this)
                if (realCamera != null) {
                    activeCameras.add(realCamera)
                } else {
                    request.virtualCamera.disconnect()
                    requests.remove(request)
                }
                continue
            }

            // Stage 4: Attach camera(s)
            realCamera.connectTo(request.virtualCamera)
            requests.remove(request)
        }
    }

    private suspend fun openCameraWithRetry(
        cameraId: CameraId,
        graphListener: GraphListener,
        scope: CoroutineScope
    ): ActiveCamera? {
        // TODO: Figure out how 1-time permissions work, and see if they can be reset without
        //   causing the application process to restart.
        check(permissions.hasCameraPermission) { "Missing camera permissions!" }

        val cameraState = retryingCameraStateOpener.openCameraWithRetry(cameraId, graphListener)
        if (cameraState == null) {
            return null
        }
        return ActiveCamera(androidCameraState = cameraState, scope = scope, channel = requestQueue)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun readRequestQueue(requests: MutableList<CameraRequest>) {
        if (requests.isEmpty()) {
            requests.add(requestQueue.receive())
        }

        // We have a request, but there is a chance we have received multiple requests while we
        // were doing other things (like opening a camera).
        while (!requestQueue.isEmpty) {
            requests.add(requestQueue.receive())
        }
    }

    internal class ActiveCamera(
        private val androidCameraState: AndroidCameraState,
        scope: CoroutineScope,
        channel: SendChannel<CameraRequest>
    ) {
        val cameraId: CameraId
            get() = androidCameraState.cameraId

        private val listenerJob: Job
        private var current: VirtualCameraState? = null

        private val wakelock = WakeLock(
            scope,
            timeout = 1000,
            callback = {
                channel.trySend(RequestClose(this)).isSuccess
            }
        )

        init {
            listenerJob = scope.launch {
                androidCameraState.state.collect {
                    if (it is CameraStateClosing || it is CameraStateClosed) {
                        wakelock.release()
                        this.cancel()
                    }
                }
            }
        }

        suspend fun connectTo(virtualCameraState: VirtualCameraState) {
            val token = wakelock.acquire()
            val previous = current
            current = virtualCameraState

            previous?.disconnect()
            virtualCameraState.connect(androidCameraState.state, token)
        }

        fun close() {
            wakelock.release()
            androidCameraState.close()
        }

        suspend fun awaitClosed() {
            androidCameraState.awaitClosed()
        }
    }
}