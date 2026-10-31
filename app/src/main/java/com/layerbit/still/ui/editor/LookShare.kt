package com.layerbit.still.ui.editor

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.layerbit.still.R
import com.layerbit.still.StillApplication
import com.layerbit.still.engine.LookCode
import com.layerbit.still.engine.Names
import com.layerbit.still.engine.Recipe
import com.layerbit.still.files.ImageStore
import com.layerbit.still.files.StillFile
import com.layerbit.still.image.LookQrImage
import com.layerbit.still.image.OutputFormat
import com.layerbit.still.ui.components.PrimaryButton
import com.layerbit.still.ui.components.SecondaryButton
import com.layerbit.still.ui.theme.StillColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Looks shared as QR codes, offline: "Share as QR" draws the current edit or a preset as a code
 * that travels in a screenshot, a print or a message; "Scan look" reads one back from a picture
 * the user picks. Nothing goes through a server, because there is nothing to go through.
 */
@Composable
fun LookShare(recipe: Recipe, presets: List<ImageStore.Preset>, onImported: (String, Recipe) -> Unit, onMessage: (String?) -> Unit) {
    val context = LocalContext.current
    val still = context.applicationContext as StillApplication
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf<Pair<String, Recipe>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val current = LookCode.lookOf(recipe)
    val defaultName = stringResource(R.string.looks_share_current)
    val noneFound = stringResource(R.string.looks_scan_none)
    val notALook = stringResource(R.string.looks_scan_not_look)

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scanning = true
        scope.launch {
            try {
                val found = withContext(Dispatchers.Default) { LookQrImage.readLook(context.contentResolver, uri) }
                if (found == null) onMessage(noneFound) else onImported(found.first, found.second)
            } catch (c: CancellationException) {
                throw c
            } catch (e: IllegalArgumentException) {
                onMessage(notALook)
            } catch (e: Exception) {
                onMessage(e.message ?: noneFound)
            } finally { scanning = false }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        SecondaryButton(
            stringResource(R.string.looks_share_qr), onClick = { choosing = true }, modifier = Modifier.weight(1f).testTag("shareLookQr"), tall = false,
            enabled = !current.isIdentity || presets.isNotEmpty()
        )
        SecondaryButton(
            stringResource(if (scanning) R.string.looks_scanning else R.string.looks_scan), enabled = !scanning, tall = false, modifier = Modifier.weight(1f),
            onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        )
    }
    Hint(stringResource(R.string.looks_qr_offline))

    if (choosing) AlertDialog(
        onDismissRequest = { choosing = false }, containerColor = StillColors.Surface,
        title = { Text(stringResource(R.string.looks_share_pick), color = StillColors.Text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!current.isIdentity) TextButton(onClick = { choosing = false; sharing = defaultName to current }) { Text(defaultName, color = StillColors.BandBright) }
                for (p in presets) TextButton(onClick = { choosing = false; sharing = p.name to p.recipe }) { Text(p.name, color = StillColors.Text) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.app_cancel), color = StillColors.Muted) } }
    )

    sharing?.let { (name, look) ->
        val code = remember(name, look) { LookCode.encode(name, look) }
        if (!LookCode.fits(code)) {
            val tooBig = stringResource(R.string.looks_qr_too_big)
            LaunchedEffect(code) { sharing = null; onMessage(tooBig) }
            return@let
        }
        QrSheet(name, code, onClose = { sharing = null }, onMessage = onMessage, still = still)
    }
}

@Composable
private fun QrSheet(name: String, code: String, onClose: () -> Unit, onMessage: (String?) -> Unit, still: StillApplication) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picture: Bitmap = remember(code, name) { LookQrImage.render(code, name) }
    var file by remember(code, name) { mutableStateOf<StillFile?>(null) }

    /** The code as a PNG in the app's scratch space, written once, for saving or sharing. */
    suspend fun png(): StillFile = file ?: withContext(Dispatchers.IO) {
        val out = still.workspace.newOutput(name, "look-qr", "png")
        out.writeBytes(still.codecs.encode(picture, OutputFormat.PNG, 100))
        StillFile(out, Names.safe(name, 40) + "-look-qr.png", StillFile.Origin.Derived("look-qr"), StillFile.MIME_PNG, picture.width, picture.height)
    }.also { file = it }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val dest = result.data?.data
        val f = file
        if (result.resultCode == android.app.Activity.RESULT_OK && dest != null && f != null) scope.launch {
            try { still.exporter.writeTo(dest, f); onMessage(context.getString(R.string.export_saved, f.displayName)) } catch (e: Exception) { onMessage(e.message) }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(StillColors.Background).verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.looks_qr_title), style = MaterialTheme.typography.titleLarge, color = StillColors.Text)
            Text(name, style = MaterialTheme.typography.titleMedium, color = StillColors.BandBright)
            Image(
                picture.asImageBitmap(), contentDescription = stringResource(R.string.looks_qr_description, name),
                contentScale = ContentScale.Fit, filterQuality = FilterQuality.None,
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(picture.width.toFloat() / picture.height).clip(RoundedCornerShape(20.dp)).testTag("lookQr")
            )
            Hint(stringResource(R.string.looks_qr_hint))
            PrimaryButton(stringResource(R.string.looks_qr_save), onClick = {
                scope.launch {
                    try { val f = png(); save.launch(still.exporter.createDocumentIntent(f.displayName, f.mimeType)) } catch (e: Exception) { onMessage(e.message) }
                }
            }, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth())
            SecondaryButton(stringResource(R.string.app_share), onClick = {
                scope.launch {
                    try { context.startActivity(still.exporter.shareIntent(png())) } catch (e: Exception) { onMessage(e.message) }
                }
            }, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth())
            TextButton(onClick = onClose) { Text(stringResource(R.string.app_close), color = StillColors.Muted) }
        }
    }
}
