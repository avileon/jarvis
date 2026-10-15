package il.co.vibit.jarvis.assistant.voice

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import il.co.vibit.jarvis.assistant.JarvisApp
import il.co.vibit.jarvis.assistant.engine.EngineEvent
import il.co.vibit.jarvis.assistant.engine.Phase
import il.co.vibit.jarvis.assistant.engine.Source
import il.co.vibit.jarvis.assistant.ui.AssistOverlay
import il.co.vibit.jarvis.assistant.ui.theme.JarvisTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The assistant overlay shown by the system (side key long-press, corner swipe, wake word).
 * Displayed by the system above other apps and above the lock screen.
 */
class JarvisSession(context: Context) : VoiceInteractionSession(context), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private val app = context.applicationContext as JarvisApp
    private val scope = MainScope()
    private var autoHide: Job? = null

    override fun onCreate() {
        // Owners must be ready before super.onCreate(), which calls onCreateContentView().
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        super.onCreate()
        scope.launch {
            app.engine.events.collect { ev ->
                when (ev) {
                    // Get out of the way so the in-call screen is visible.
                    is EngineEvent.CallStarted -> hide()
                    EngineEvent.ConversationEnded -> scheduleAutoHide()
                }
            }
        }
    }

    override fun onCreateContentView(): View = ComposeView(context).apply {
        setViewTreeLifecycleOwner(this@JarvisSession)
        setViewTreeSavedStateRegistryOwner(this@JarvisSession)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent {
            JarvisTheme {
                AssistOverlay(
                    engine = app.engine,
                    onMic = { if (app.engine.isBusy) app.engine.cancel() else app.engine.start(Source.ASSIST) },
                    onClose = { app.engine.cancel(); hide() },
                )
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        registry.currentState = Lifecycle.State.RESUMED
        autoHide?.cancel()
        // The wake word service already started the conversation; otherwise start one now.
        if (args?.getString(ARG_SOURCE) != SOURCE_WAKE) app.engine.start(Source.ASSIST)
    }

    override fun onHide() {
        autoHide?.cancel()
        if (app.engine.state.value.phase != Phase.CALLING) app.engine.cancel()
        registry.currentState = Lifecycle.State.CREATED
        super.onHide()
    }

    override fun onDestroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        scope.cancel()
        super.onDestroy()
    }

    private fun scheduleAutoHide() {
        autoHide?.cancel()
        autoHide = scope.launch {
            delay(2500)
            if (app.engine.state.value.phase == Phase.IDLE) hide()
        }
    }

    companion object {
        const val ARG_SOURCE = "jarvis_source"
        const val SOURCE_WAKE = "wake"
    }
}
