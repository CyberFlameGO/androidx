/*
 * Copyright 2020 The Android Open Source Project
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

package androidx.wear.watchface.client.test

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.test.core.app.ApplicationProvider
import androidx.wear.watchface.control.IWatchFaceInstanceServiceStub
import androidx.wear.watchface.control.WatchFaceControlService
import kotlinx.coroutines.MainScope

/**
 * Test shim to allow us to connect to WatchFaceControlService from
 * [WatchFaceControlClientTest] and to optionally override the reported API version.
 */
@RequiresApi(Build.VERSION_CODES.O_MR1)
public class WatchFaceControlTestService : WatchFaceControlService() {
    public companion object {
        /**
         * If non-null this overrides the API version reported by [IWatchFaceInstanceServiceStub].
         */
        public var apiVersionOverride: Int? = null
    }

    private val realService =
        @RequiresApi(Build.VERSION_CODES.O_MR1)
        object : WatchFaceControlService() {
            override fun createServiceStub(): IWatchFaceInstanceServiceStub =
                @RequiresApi(Build.VERSION_CODES.O_MR1)
                object : IWatchFaceInstanceServiceStub(
                    this@WatchFaceControlTestService,
                    MainScope()
                ) {
                    override fun getApiVersion(): Int = apiVersionOverride ?: super.getApiVersion()
                }

            init {
                setContext(ApplicationProvider.getApplicationContext<Context>())
            }
        }

    @RequiresApi(Build.VERSION_CODES.O_MR1)
    override fun onBind(intent: Intent?): IBinder? = realService.onBind(intent)
}
