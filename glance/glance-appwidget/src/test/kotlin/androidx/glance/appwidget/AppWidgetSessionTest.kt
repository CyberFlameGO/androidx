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

package androidx.glance.appwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.Emittable
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionModifier
import androidx.glance.action.LambdaAction
import androidx.glance.layout.EmittableBox
import androidx.glance.state.ConfigManager
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.EmittableText
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AppWidgetSessionTest {

    private val id = AppWidgetId(123)
    private val widget = SampleGlanceAppWidget {}
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val defaultOptions =
        optionsBundleOf(listOf(DpSize(100.dp, 50.dp), DpSize(50.dp, 100.dp)))
    private val testState = TestGlanceState()
    private val session = AppWidgetSession(widget, id, defaultOptions, testState)

    @Before
    fun setUp() {
        val appWidgetManager = Shadows.shadowOf(
            context.getSystemService(Context.APPWIDGET_SERVICE) as AppWidgetManager
        )
        appWidgetManager.addBoundWidget(id.appWidgetId, AppWidgetProviderInfo())
    }

    @Test
    fun createRootEmittable() = runTest {
        assertIs<RemoteViewsRoot>(session.createRootEmittable())
    }

    @Test
    fun provideGlanceRunsGlance() = runTest {
        session.provideGlance(context).first()
    }

    @Test
    fun provideGlanceEmitsIgnoreResultForNullContent() = runTest {
        // The session starts out with null content, so we can check that here.
        val initialContent = session.provideGlance(context).first()
        val root = runTestingComposition(initialContent)
        assertThat(root.shouldIgnoreResult()).isTrue()
    }

    @Test
    fun processEmittableTree() = runTest {
        val root = RemoteViewsRoot(maxDepth = 1).apply {
            children += EmittableText().apply {
                text = "hello"
            }
        }

        session.processEmittableTree(context, root)
        context.applyRemoteViews(session.lastRemoteViews!!).let {
            val text = assertIs<TextView>(it)
            assertThat(text.text).isEqualTo("hello")
        }
    }

    @Test
    fun processEmittableTree_ignoresResult() = runTest {
        val root = RemoteViewsRoot(maxDepth = 1).apply {
            children += EmittableIgnoreResult()
        }

        session.processEmittableTree(context, root)
        assertThat(session.lastRemoteViews).isNull()
    }

    @Test
    fun processEmittableTree_catchesException() = runTest {
        val root = RemoteViewsRoot(maxDepth = 1).apply {
            children += object : Emittable {
                override var modifier: GlanceModifier = GlanceModifier
                override fun copy() = this
            }
        }

        session.processEmittableTree(context, root)
        assertThat(session.lastRemoteViews!!.layoutId).isEqualTo(widget.errorUiLayout)
    }

    @Test
    fun processEvent_unknownAction() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { session.processEvent(context, Any()) }
        }
    }

    @Test
    fun processEvent_updateGlance() = runTest {
        session.processEvent(context, AppWidgetSession.UpdateGlanceState)
        assertThat(testState.getValueCalls).containsExactly(id.toSessionKey())
    }

    @Test
    fun updateGlance() = runTest {
        session.updateGlance()
        session.receiveEvents(context) {
            this@runTest.launch { session.close() }
        }
        assertThat(testState.getValueCalls).containsExactly(id.toSessionKey())
    }

    @Test
    fun processEvent_runLambda() = runTest {
        var didRunFirst = false
        var didRunSecond = false
        session.processEmittableTree(context, RemoteViewsRoot(1).apply {
            children += EmittableBox().apply {
                modifier = GlanceModifier.then(ActionModifier(LambdaAction("123") {
                    didRunFirst = true
                }))
            }
            children += EmittableBox().apply {
                modifier = GlanceModifier.then(ActionModifier(LambdaAction("123") {
                    didRunSecond = true
                }))
            }
        })
        session.processEvent(context, AppWidgetSession.RunLambda("123+0"))
        assertTrue(didRunFirst)
        assertFalse(didRunSecond)

        didRunFirst = false
        session.processEvent(context, AppWidgetSession.RunLambda("123+1"))
        assertTrue(didRunSecond)
        assertFalse(didRunFirst)
    }

    @Test
    fun runLambda() = runTest {
        var didRunFirst = false
        var didRunSecond = false
        session.processEmittableTree(context, RemoteViewsRoot(1).apply {
            children += EmittableBox().apply {
                modifier = GlanceModifier.then(ActionModifier(LambdaAction("123") {
                    didRunFirst = true
                }))
            }
            children += EmittableBox().apply {
                modifier = GlanceModifier.then(ActionModifier(LambdaAction("123") {
                    didRunSecond = true
                    this@runTest.launch { session.close() }
                }))
            }
        })

        session.runLambda("123+0")
        session.runLambda("123+1")
        session.receiveEvents(context) {}
        assertTrue(didRunFirst)
        assertTrue(didRunSecond)
    }

    private class SampleGlanceAppWidget(
        val ui: @Composable () -> Unit,
    ) : GlanceAppWidget() {
        @Composable
        override fun Content() {
            ui()
        }
    }

    private class TestGlanceState : ConfigManager {

        val getValueCalls = mutableListOf<String>()
        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> getValue(
            context: Context,
            definition: GlanceStateDefinition<T>,
            fileKey: String
        ): T {
            assertIs<PreferencesGlanceStateDefinition>(definition)
            getValueCalls.add(fileKey)
            return definition.getDataStore(context, fileKey).also {
                definition.getLocation(context, fileKey).delete()
            }.data.first() as T
        }

        override suspend fun <T> updateValue(
            context: Context,
            definition: GlanceStateDefinition<T>,
            fileKey: String,
            updateBlock: suspend (T) -> T
        ): T {
            TODO("Not yet implemented")
        }

        override suspend fun deleteStore(
            context: Context,
            definition: GlanceStateDefinition<*>,
            fileKey: String
        ) {
            TODO("Not yet implemented")
        }
    }
}
