package colourfilm.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import colourfilm.app.ui.ColourFilmTheme
import colourfilm.app.ui.HomeScreen
import colourfilm.app.ui.ProcessingScreen
import colourfilm.app.ui.ResultScreen
import colourfilm.app.ui.ReviewScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    // System document picker: covers local storage, SD cards, Google Photos and other gallery
    // providers. Read-only; selecting a file never uploads it anywhere.
    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.openVideo(it) }
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (savedInstanceState == null) handleShare(intent)

        setContent {
            ColourFilmTheme {
                val screen by vm.screen.collectAsState()
                val msg by vm.message.collectAsState()
                val snack = remember { SnackbarHostState() }
                LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); vm.message.value = null } }
                BackHandler(enabled = screen !is Screen.Home) { vm.back() }
                Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
                    Box(Modifier.fillMaxSize()) {
                        when (val s = screen) {
                            Screen.Home -> HomeScreen(vm, pad) { pick.launch(arrayOf("video/*")) }
                            is Screen.Review -> ReviewScreen(vm, s.info, pad)
                            is Screen.Processing -> ProcessingScreen(vm, s.jobId, pad)
                            is Screen.Result -> ResultScreen(vm, s.jobId, pad) { startActivity(it) }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            uri?.let { vm.openVideo(it) }
        }
    }
}
