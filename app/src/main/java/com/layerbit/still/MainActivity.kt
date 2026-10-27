package com.layerbit.still

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
import com.layerbit.still.ui.Arrival
import com.layerbit.still.ui.StillApp
import com.layerbit.still.ui.components.ProvideLivelyClock
import com.layerbit.still.ui.theme.StillColors
import com.layerbit.still.ui.theme.StillTheme

class MainActivity : ComponentActivity() {

    private val still: StillApplication get() = application as StillApplication

    /** Bumped on every arrival from outside, which is what tells the UI to navigate. */
    private var ticket by mutableIntStateOf(0)
    private var arrival by mutableStateOf<Arrival?>(null)

    /** The front door: any number of pictures. One edits; several go to the pipeline. */
    private val pickPictures = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris -> arrive(uris) }

    private val pickOne = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) arrive(listOf(uri)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by still.settings.state.collectAsState()
            StillTheme(theme = settings.theme) {
                LaunchedEffect(StillColors.isDark) {
                    val style = if (StillColors.isDark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                ProvideLivelyClock(enabled = settings.livelyIcons) {
                    StillApp(
                        settings = settings,
                        onSettingsChange = { transform -> still.settings.update(transform) },
                        arrivalTicket = ticket,
                        arrival = arrival,
                        onPickPictures = { pickPictures.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onPickOne = { pickOne.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
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

    /** Pictures and shortcuts from outside: share, "edit in", "open with", launcher shortcuts. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data?.scheme == SHORTCUT_SCHEME) {
            when (val which = intent.data?.lastPathSegment) {
                "EDIT" -> pickPictures.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                else -> { arrival = Arrival.Shortcut(if (which == "RESIZE_KB") "under" else if (which == "STRIP_METADATA") "clean" else null); ticket += 1 }
            }
            return
        }
        if (intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            arrive(intent.parcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty())
            return
        }
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data
            Intent.ACTION_SEND -> intent.parcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) arrive(listOf(uri))
    }

    private fun arrive(uris: List<Uri>) {
        when {
            uris.isEmpty() -> return
            uris.size == 1 -> arrival = Arrival.One(uris.first())
            else -> { still.handoff.offer(uris); arrival = Arrival.Many }
        }
        ticket += 1
    }

    companion object {
        private const val SHORTCUT_SCHEME = "still"
    }
}

private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtra(name: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(name, T::class.java)
    else @Suppress("DEPRECATION") getParcelableExtra(name) as? T

private inline fun <reified T : android.os.Parcelable> Intent.parcelableArrayListExtra(name: String): ArrayList<T>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableArrayListExtra(name, T::class.java)
    else @Suppress("DEPRECATION") getParcelableArrayListExtra(name)
