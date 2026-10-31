package com.layerbit.still.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.layerbit.still.StillApplication

/**
 * Wraps a batch's Run: before the first batch of [MIN_FOR_ASK] or more pictures, asks once for
 * permission to show its progress in the shade, then runs whatever the answer. Never asked on
 * a small batch, never asked twice.
 */
@Composable
fun rememberBatchRun(count: () -> Int, run: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentRun by rememberUpdatedState(run)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { currentRun() }
    return remember(context) {
        {
            val prompts = (context.applicationContext as StillApplication).prompts
            val needs = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (needs && !prompts.notificationsAsked && count() >= MIN_FOR_ASK) {
                prompts.notificationsAsked = true
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else currentRun()
        }
    }
}

private const val MIN_FOR_ASK = 3
