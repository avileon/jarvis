package il.co.vibit.jarvis.assistant.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import il.co.vibit.jarvis.assistant.ui.theme.JarvisTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refresh()
    }

    private val importModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importWakeModel(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleLaunch(intent)
        setContent {
            JarvisTheme {
                var screen by rememberSaveable { mutableStateOf("main") }
                BackHandler(enabled = screen != "main") { screen = "main" }
                when (screen) {
                    "settings" -> SettingsScreen(
                        vm, onBack = { screen = "main" },
                        onRequestPermissions = ::requestPermissions,
                        onImportModel = { importModel.launch(arrayOf("application/octet-stream", "*/*")) },
                    )
                    else -> MainScreen(vm, onSettings = { screen = "settings" }, onRequestPermissions = ::requestPermissions)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunch(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }

    /** ASSIST / VOICE_COMMAND intents and the keyguard affordance start listening right away. */
    private fun handleLaunch(intent: Intent?) {
        intent ?: return
        val fromKeyguard = intent.getBooleanExtra(EXTRA_FROM_KEYGUARD, false)
        if (fromKeyguard) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        val listen = intent.getBooleanExtra(EXTRA_START_LISTENING, false) ||
            intent.action == Intent.ACTION_ASSIST || intent.action == Intent.ACTION_VOICE_COMMAND
        if (listen) {
            vm.refresh()
            if (vm.system.value.permissions.mic) vm.startListening() else requestPermissions()
        }
    }

    private fun requestPermissions() {
        permissions.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        )
    }

    companion object {
        const val EXTRA_START_LISTENING = "start_listening"
        const val EXTRA_FROM_KEYGUARD = "from_keyguard"
    }
}
