package com.layerbit.studio

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import com.layerbit.studio.core.Exported
import com.layerbit.studio.ui.StudioApp
import java.io.File

class MainActivity:ComponentActivity() {
    private val vm:StudioViewModel by viewModels()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { StudioApp(vm,::share) }
    }
    private fun share(exports:List<Exported>) {
        if(exports.isEmpty()) return
        val uris=ArrayList(exports.map { FileProvider.getUriForFile(this,"$packageName.files",File(it.path)) })
        val intent=Intent(if(uris.size==1)Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type=if(exports.map { it.mime }.distinct().size==1) exports.first().mime else "image/*"
            if(uris.size==1) putExtra(Intent.EXTRA_STREAM,uris.first()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris)
            clipData=ClipData.newRawUri("Edited images",uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent,"Share new copy")) }.onFailure { vm.message("No app could accept these images.") }
    }
}
