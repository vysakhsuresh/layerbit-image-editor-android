package com.layerbit.studio.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.layerbit.studio.R
import com.layerbit.studio.StudioApplication
import com.layerbit.studio.files.ImageStore
import com.layerbit.studio.files.StudioFile
import com.layerbit.studio.ops.ToolId
import com.layerbit.studio.prefs.Settings
import com.layerbit.studio.ui.about.AboutScreen
import com.layerbit.studio.ui.components.StudioTopBar
import com.layerbit.studio.ui.editor.EditorScreen
import com.layerbit.studio.ui.home.HomeScreen
import com.layerbit.studio.ui.icons.iconFor
import com.layerbit.studio.ui.intro.IntroScreen
import com.layerbit.studio.ui.pipeline.PipelineRoute
import com.layerbit.studio.ui.settings.SettingsScreen
import com.layerbit.studio.ui.theme.GroupAccents
import com.layerbit.studio.ui.theme.StudioColors
import com.layerbit.studio.ui.tools.ToolRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Routes {
    const val HOME = "home"
    const val ABOUT = "about"
    const val SETTINGS = "settings"
    const val COLLAGE = "collage"
    const val PIPELINE = "pipeline?preset={preset}"
    const val EDITOR = "editor?project={project}&uri={uri}&panel={panel}"

    fun pipeline(preset: String? = null) = if (preset == null) "pipeline" else "pipeline?preset=$preset"
    fun editor(project: String? = null, uri: String? = null, panel: ToolId.Panel = ToolId.Panel.CROP): String {
        val args = buildList {
            if (project != null) add("project=" + Uri.encode(project))
            if (uri != null) add("uri=" + Uri.encode(uri))
            add("panel=" + panel.name)
        }
        return "editor?" + args.joinToString("&")
    }
}

/** What arrived from outside and where it goes. */
sealed interface Arrival {
    data class One(val uri: Uri) : Arrival
    /** Several pictures, already handed to the pipeline through Handoff. */
    data object Many : Arrival
    data class Shortcut(val preset: String?) : Arrival
}

/**
 * Navigation. One picture from the picker, a share or "edit in" goes to the editor; several go
 * to the batch pipeline; a shortcut lands on the pipeline with its step on.
 */
@Composable
fun StudioApp(
    settings: Settings,
    onSettingsChange: ((Settings) -> Settings) -> Unit,
    arrivalTicket: Int,
    arrival: Arrival?,
    onPickPictures: () -> Unit,
    onPickOne: () -> Unit
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val studio = context.applicationContext as StudioApplication
    val scope = rememberCoroutineScope()

    var projects by remember { mutableStateOf<List<ImageStore.Project>>(emptyList()) }
    var projectsStamp by remember { mutableLongStateOf(0L) }
    LaunchedEffect(projectsStamp) { projects = withContext(Dispatchers.IO) { studio.store.projects() } }
    var removing by remember { mutableStateOf<ImageStore.Project?>(null) }

    LaunchedEffect(arrivalTicket) {
        if (arrivalTicket == 0) return@LaunchedEffect
        when (val a = arrival) {
            is Arrival.One -> navController.navigate(Routes.editor(uri = a.uri.toString())) { launchSingleTop = true }
            Arrival.Many -> navController.navigate(Routes.pipeline()) { launchSingleTop = true }
            is Arrival.Shortcut -> navController.navigate(Routes.pipeline(a.preset)) { launchSingleTop = true }
            null -> Unit
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(StudioColors.Background).windowInsetsPadding(WindowInsets.systemBars)) {
        NavHost(navController = navController, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                LaunchedEffect(Unit) { projectsStamp = System.nanoTime() }
                HomeScreen(
                    projects = projects,
                    thumbnail = { studio.store.thumbnail(it) },
                    onOpenPictures = onPickPictures,
                    onOpenProject = { p -> navController.navigate(Routes.editor(project = p.id)) },
                    onForgetProject = { removing = it },
                    onEditOne = onPickOne,
                    onProcessMany = { preset -> navController.navigate(Routes.pipeline(preset)) },
                    onCollage = { navController.navigate(Routes.COLLAGE) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                    onAbout = { navController.navigate(Routes.ABOUT) }
                )
            }
            composable(
                Routes.EDITOR,
                arguments = listOf(
                    navArgument("project") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("uri") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("panel") { type = NavType.StringType; defaultValue = ToolId.Panel.CROP.name }
                )
            ) { entry ->
                val panel = ToolId.Panel.entries.firstOrNull { it.name == entry.arguments?.getString("panel") } ?: ToolId.Panel.CROP
                EditorScreen(
                    projectId = entry.arguments?.getString("project"),
                    uri = entry.arguments?.getString("uri"),
                    startPanel = panel,
                    onBack = { navController.popBackStack() },
                    onStripMetadata = { file -> studio.handoff.offer(listOf(Uri.fromFile(file.file))); navController.navigate(Routes.pipeline("clean")) }
                )
            }
            composable(Routes.PIPELINE, arguments = listOf(navArgument("preset") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
                Column {
                    StudioTopBar(title = ToolId.PROCESS.title, subtitle = ToolId.PROCESS.summary, onBack = { navController.popBackStack() }, iconRes = iconFor(ToolId.PROCESS), accent = GroupAccents.of(ToolId.Group.BATCH))
                    PipelineRoute(preset = entry.arguments?.getString("preset"), onOpenInEditor = { file -> adoptAndEdit(studio, file, scope) { id -> navController.navigate(Routes.editor(project = id)) } })
                }
            }
            composable(Routes.COLLAGE) {
                Column {
                    StudioTopBar(title = ToolId.COLLAGE.title, subtitle = ToolId.COLLAGE.summary, onBack = { navController.popBackStack() }, iconRes = iconFor(ToolId.COLLAGE), accent = GroupAccents.of(ToolId.Group.BATCH))
                    ToolRoute(tool = ToolId.COLLAGE, onOpenInEditor = { file -> adoptAndEdit(studio, file, scope) { id -> navController.navigate(Routes.editor(project = id)) } }, onBack = { navController.popBackStack() })
                }
            }
            composable(Routes.SETTINGS) {
                var workspaceBytes by remember { mutableLongStateOf(0L) }
                var projectBytes by remember { mutableLongStateOf(0L) }
                var count by remember { mutableStateOf(0) }
                LaunchedEffect(Unit) { withContext(Dispatchers.IO) { workspaceBytes = studio.workspace.sizeBytes(); projectBytes = studio.store.projectsBytes(); count = studio.store.projects().size } }
                Column {
                    StudioTopBar(title = stringResource(R.string.app_settings), onBack = { navController.popBackStack() })
                    SettingsScreen(
                        settings = settings, workspaceBytes = workspaceBytes, projectBytes = projectBytes, projectCount = count, onChange = onSettingsChange,
                        onClearWorkspace = { studio.workspace.clear(); studio.store.clearImports(); workspaceBytes = 0 },
                        onClearProjects = { scope.launch(Dispatchers.IO) { studio.store.projects().forEach { studio.store.remove(it) }; studio.store.clearThumbnails(); projectBytes = 0; count = 0; projectsStamp = System.nanoTime() } }
                    )
                }
            }
            composable(Routes.ABOUT) {
                Column {
                    StudioTopBar(title = stringResource(R.string.about_title), onBack = { navController.popBackStack() })
                    AboutScreen()
                }
            }
        }

        removing?.let { p ->
            AlertDialog(
                onDismissRequest = { removing = null }, containerColor = StudioColors.Surface,
                title = { Text(stringResource(R.string.home_remove_title), color = StudioColors.Text) },
                text = { Text(stringResource(R.string.home_remove_body), color = StudioColors.Muted) },
                confirmButton = { TextButton(onClick = { scope.launch(Dispatchers.IO) { studio.store.remove(p); projectsStamp = System.nanoTime() }; removing = null }) { Text(stringResource(R.string.app_delete), color = StudioColors.Failed) } },
                dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.app_cancel), color = StudioColors.Muted) } }
            )
        }

        val arrivedWithSomething = arrivalTicket > 0
        var showIntro by remember { mutableStateOf(!studio.prompts.introSeen) }
        if (showIntro && !arrivedWithSomething) {
            BackHandler { showIntro = false; studio.prompts.introSeen = true }
            IntroScreen(onDone = { studio.prompts.introSeen = true; showIntro = false })
        }
    }
}

private fun adoptAndEdit(studio: StudioApplication, file: StudioFile, scope: kotlinx.coroutines.CoroutineScope, open: (String) -> Unit) {
    scope.launch {
        val p = runCatching { studio.store.adoptAsProject(file) }.getOrNull() ?: return@launch
        open(p.id)
    }
}
