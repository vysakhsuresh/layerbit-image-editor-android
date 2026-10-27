package com.layerbit.studio

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.layerbit.studio.ops.ToolId
import com.layerbit.studio.ui.StudioApp
import com.layerbit.studio.ui.components.ProvideLivelyClock
import com.layerbit.studio.ui.theme.StudioColors
import com.layerbit.studio.ui.theme.StudioTheme

class MainActivity : ComponentActivity() {

    private val studio: StudioApplication get() = application as StudioApplication

    /** Bumped on every single picture that arrives, which tells the UI to open the editor. */
    private var openTicket by mutableIntStateOf(0)
    private var incoming by mutableStateOf<Uri?>(null)

    /** A tool to go straight to, from a shortcut or a share of several pictures. */
    private var jumpToTool by mutableStateOf<ToolId?>(null)

    private val pickPicture = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) open(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by studio.settings.state.collectAsState()
            StudioTheme(theme = settings.theme) {
                // The status and navigation bar glyphs, re-decided once the palette is known;
                // enableEdgeToEdge's default reads the phone's night mode, not this app's choice.
                LaunchedEffect(StudioColors.isDark) {
                    val style = if (StudioColors.isDark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                ProvideLivelyClock(enabled = settings.livelyIcons) {
                    StudioApp(
                        settings = settings,
                        onSettingsChange = { transform -> studio.settings.update(transform) },
                        openTicket = openTicket,
                        incomingUri = incoming,
                        jumpToTool = jumpToTool,
                        onJumpHandled = { jumpToTool = null },
                        onPickPicture = { pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    )
                }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Pictures and shortcuts arriving from outside. One picture opens in the editor; several go
     * to Resize, with the whole set loaded, because an editor shows one and a batch is the
     * obvious thing to want with five. A shortcut arrives as a VIEW on a studio:// Uri.
     */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data?.scheme == SHORTCUT_SCHEME) {
            jumpToTool = ToolId.byName(intent.data?.lastPathSegment)
            return
        }
        if (intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            val uris = intent.parcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            when {
                uris.isEmpty() -> Unit
                uris.size == 1 -> open(uris.first())
                else -> { studio.handoff.offer(uris); jumpToTool = ToolId.RESIZE }
            }
            return
        }
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data
            Intent.ACTION_SEND -> intent.parcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) open(uri)
    }

    private fun open(uri: Uri) {
        incoming = uri
        openTicket += 1
    }

    companion object {
        private const val SHORTCUT_SCHEME = "studio"
    }
}

private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtra(name: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(name, T::class.java)
    else @Suppress("DEPRECATION") getParcelableExtra(name) as? T

private inline fun <reified T : android.os.Parcelable> Intent.parcelableArrayListExtra(name: String): ArrayList<T>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableArrayListExtra(name, T::class.java)
    else @Suppress("DEPRECATION") getParcelableArrayListExtra(name)
