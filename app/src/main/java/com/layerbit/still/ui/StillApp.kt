package com.layerbit.still.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import com.layerbit.still.R
import com.layerbit.still.StillApplication
import com.layerbit.still.files.ImageStore
import com.layerbit.still.files.StillFile
import com.layerbit.still.ops.ToolId
import com.layerbit.still.prefs.Settings
import com.layerbit.still.ui.about.AboutScreen
import com.layerbit.still.ui.pages.ContactScreen
import com.layerbit.still.ui.pages.GuideScreen
import com.layerbit.still.ui.pages.ToolsScreen
import com.layerbit.still.ui.components.StillTopBar
import com.layerbit.still.ui.editor.EditorScreen
import com.layerbit.still.ui.home.HomeScreen
import com.layerbit.still.ui.icons.iconFor
import com.layerbit.still.ui.intro.IntroScreen
import com.layerbit.still.ui.pipeline.PipelineRoute
import com.layerbit.still.ui.settings.SettingsScreen
import com.layerbit.still.ui.theme.GroupAccents
import com.layerbit.still.ui.theme.StillColors
import com.layerbit.still.ui.tools.ToolRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Routes {
    const val HOME = "home"
    const val ABOUT = "about"
    const val SETTINGS = "settings"
    const val COLLAGE = "collage"
    const val TOOLS = "tools"
    const val GUIDE = "guide"
    const val CONTACT = "contact"
    const val PIPELINE = "pipeline?preset={preset}"
    const val EDITOR = "editor?project={project}&uri={uri}&panel={panel}&tool={tool}"

    fun pipeline(preset: String? = null) = if (preset == null) "pipeline" else "pipeline?preset=$preset"
    fun editor(project: String? = null, uri: String? = null, panel: ToolId.Panel = ToolId.Panel.CROP, tool: ToolId? = null): String {
        val args = buildList {
            if (project != null) add("project=" + Uri.encode(project))
            if (uri != null) add("uri=" + Uri.encode(uri))
            add("panel=" + panel.name)
            if (tool != null) add("tool=" + tool.name)
        }
        return "editor?" + args.joinToString("&")
    }
}

/** What arrived from outside and where it goes. */
sealed interface Arrival {
    /** One picture for the editor, opened on a tool's panel when a quick tool asked for it. */
    data class One(val uri: Uri, val tool: ToolId? = null) : Arrival
    /** Several pictures, already handed to the pipeline through Handoff. */
    data object Many : Arrival
    data class Shortcut(val preset: String?) : Arrival
}

/**
 * Navigation. One picture from the picker, a share or "edit in" goes to the editor; several go
 * to the batch pipeline; a shortcut lands on the pipeline with its step on.
 */
@Composable
fun StillApp(
    settings: Settings,
    onSettingsChange: ((Settings) -> Settings) -> Unit,
    arrivalTicket: Int,
    arrival: Arrival?,
    onPickPictures: () -> Unit,
    onPickOne: (ToolId?) -> Unit
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val still = context.applicationContext as StillApplication
    val scope = rememberCoroutineScope()

    var projects by remember { mutableStateOf<List<ImageStore.Project>>(emptyList()) }
    var projectsStamp by remember { mutableLongStateOf(0L) }
    LaunchedEffect(projectsStamp) { projects = withContext(Dispatchers.IO) { still.store.projects() } }
    var removing by remember { mutableStateOf<ImageStore.Project?>(null) }

    LaunchedEffect(arrivalTicket) {
        if (arrivalTicket == 0) return@LaunchedEffect
        when (val a = arrival) {
            is Arrival.One -> navController.navigate(Routes.editor(uri = a.uri.toString(), panel = a.tool?.panel ?: ToolId.Panel.CROP, tool = a.tool)) { launchSingleTop = true }
            Arrival.Many -> navController.navigate(Routes.pipeline()) { launchSingleTop = true }
            is Arrival.Shortcut -> navController.navigate(Routes.pipeline(a.preset)) { launchSingleTop = true }
            null -> Unit
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(StillColors.Background).windowInsetsPadding(WindowInsets.systemBars)) {
        // Screens arrive from a little below and settle; the one leaving fades. Reversed on the way back.
        NavHost(
            navController = navController, startDestination = Routes.HOME,
            enterTransition = { fadeIn(tween(240)) + slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it / 12 } + scaleIn(tween(280), initialScale = 0.965f) },
            exitTransition = { fadeOut(tween(180)) + scaleOut(tween(220), targetScale = 1.02f) },
            popEnterTransition = { fadeIn(tween(240)) + scaleIn(tween(280), initialScale = 1.03f) },
            popExitTransition = { fadeOut(tween(180)) + slideOutVertically(tween(240)) { it / 12 } + scaleOut(tween(220), targetScale = 0.965f) }
        ) {
            composable(Routes.HOME) {
                LaunchedEffect(Unit) { projectsStamp = System.nanoTime() }
                HomeScreen(
                    projects = projects,
                    thumbnail = { still.store.thumbnail(it) },
                    onOpenPictures = onPickPictures,
                    onOpenProject = { p -> navController.navigate(Routes.editor(project = p.id)) },
                    onForgetProject = { removing = it },
                    onEditOne = { onPickOne(null) },
                    onProcessMany = { preset -> navController.navigate(Routes.pipeline(preset)) },
                    onQuickTool = { tool -> onPickOne(tool) },
                    recentTools = settings.recentTools,
                    pinnedTools = settings.favouriteTools,
                    onUsed = { tool -> still.settings.rememberTool(tool.name) },
                    onCollage = { navController.navigate(Routes.COLLAGE) },
                    onTools = { navController.navigate(Routes.TOOLS) },
                    onGuide = { navController.navigate(Routes.GUIDE) },
                    onContact = { navController.navigate(Routes.CONTACT) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                    onAbout = { navController.navigate(Routes.ABOUT) }
                )
            }
            composable(Routes.TOOLS) {
                Column {
                    StillTopBar(title = stringResource(R.string.tools_page_title), subtitle = stringResource(R.string.tools_page_subtitle), onBack = { navController.popBackStack() })
                    ToolsScreen(recent = settings.recentTools, pinned = settings.favouriteTools, onPin = { still.settings.toggleFavourite(it.name) }, onTool = { tool ->
                        still.settings.rememberTool(tool.name)
                        when {
                            tool.mode == ToolId.Mode.EDITOR -> onPickOne(tool)
                            tool == ToolId.COLLAGE -> navController.navigate(Routes.COLLAGE)
                            else -> navController.navigate(Routes.pipeline(presetFor(tool)))
                        }
                    })
                }
            }
            composable(Routes.GUIDE) {
                Column {
                    StillTopBar(title = stringResource(R.string.guide_title), onBack = { navController.popBackStack() })
                    GuideScreen()
                }
            }
            composable(Routes.CONTACT) {
                Column {
                    StillTopBar(title = stringResource(R.string.contact_title), onBack = { navController.popBackStack() })
                    ContactScreen()
                }
            }
            composable(
                Routes.EDITOR,
                arguments = listOf(
                    navArgument("project") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("uri") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("panel") { type = NavType.StringType; defaultValue = ToolId.Panel.CROP.name },
                    navArgument("tool") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) { entry ->
                val panel = ToolId.Panel.entries.firstOrNull { it.name == entry.arguments?.getString("panel") } ?: ToolId.Panel.CROP
                EditorScreen(
                    projectId = entry.arguments?.getString("project"),
                    uri = entry.arguments?.getString("uri"),
                    startPanel = panel,
                    startTool = ToolId.byName(entry.arguments?.getString("tool")),
                    onBack = { navController.popBackStack() },
                    onStripMetadata = { file -> still.handoff.offer(listOf(Uri.fromFile(file.file))); navController.navigate(Routes.pipeline("clean")) }
                )
            }
            composable(Routes.PIPELINE, arguments = listOf(navArgument("preset") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
                Column {
                    StillTopBar(title = ToolId.PROCESS.title, subtitle = ToolId.PROCESS.summary, onBack = { navController.popBackStack() }, iconRes = iconFor(ToolId.PROCESS), accent = GroupAccents.of(ToolId.Group.BATCH))
                    PipelineRoute(preset = entry.arguments?.getString("preset"), onOpenInEditor = { file -> adoptAndEdit(still, file, scope) { id -> navController.navigate(Routes.editor(project = id)) } })
                }
            }
            composable(Routes.COLLAGE) {
                Column {
                    StillTopBar(title = ToolId.COLLAGE.title, subtitle = ToolId.COLLAGE.summary, onBack = { navController.popBackStack() }, iconRes = iconFor(ToolId.COLLAGE), accent = GroupAccents.of(ToolId.Group.BATCH))
                    ToolRoute(tool = ToolId.COLLAGE, onOpenInEditor = { file -> adoptAndEdit(still, file, scope) { id -> navController.navigate(Routes.editor(project = id)) } }, onBack = { navController.popBackStack() })
                }
            }
            composable(Routes.SETTINGS) {
                var workspaceBytes by remember { mutableLongStateOf(0L) }
                var projectBytes by remember { mutableLongStateOf(0L) }
                var count by remember { mutableStateOf(0) }
                LaunchedEffect(Unit) { withContext(Dispatchers.IO) { workspaceBytes = still.workspace.sizeBytes(); projectBytes = still.store.projectsBytes(); count = still.store.projects().size } }
                Column {
                    StillTopBar(title = stringResource(R.string.app_settings), onBack = { navController.popBackStack() })
                    SettingsScreen(
                        settings = settings, workspaceBytes = workspaceBytes, projectBytes = projectBytes, projectCount = count, onChange = onSettingsChange,
                        onClearWorkspace = { still.workspace.clear(); still.store.clearImports(); workspaceBytes = 0 },
                        onClearProjects = { scope.launch(Dispatchers.IO) { still.store.projects().forEach { still.store.remove(it) }; still.store.clearThumbnails(); projectBytes = 0; count = 0; projectsStamp = System.nanoTime() } }
                    )
                }
            }
            composable(Routes.ABOUT) {
                Column {
                    StillTopBar(title = stringResource(R.string.about_title), onBack = { navController.popBackStack() })
                    AboutScreen(onGuide = { navController.navigate(Routes.GUIDE) }, onContact = { navController.navigate(Routes.CONTACT) }, onTools = { navController.navigate(Routes.TOOLS) })
                }
            }
        }

        removing?.let { p ->
            AlertDialog(
                onDismissRequest = { removing = null }, containerColor = StillColors.Surface,
                title = { Text(stringResource(R.string.home_remove_title), color = StillColors.Text) },
                text = { Text(stringResource(R.string.home_remove_body), color = StillColors.Muted) },
                confirmButton = { TextButton(onClick = { scope.launch(Dispatchers.IO) { still.store.remove(p); projectsStamp = System.nanoTime() }; removing = null }) { Text(stringResource(R.string.app_delete), color = StillColors.Failed) } },
                dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.app_cancel), color = StillColors.Muted) } }
            )
        }

        val arrivedWithSomething = arrivalTicket > 0
        var showIntro by remember { mutableStateOf(!still.prompts.introSeen) }
        if (showIntro && !arrivedWithSomething) {
            BackHandler { showIntro = false; still.prompts.introSeen = true }
            IntroScreen(onDone = { still.prompts.introSeen = true; showIntro = false })
        }
    }
}

/** The batch screen preset a batch tool opens with. */
private fun presetFor(tool: ToolId): String? = when (tool) {
    ToolId.RESIZE -> "resize"; ToolId.RESIZE_KB -> "under"; ToolId.COMPRESS, ToolId.CONVERT -> "convert"; ToolId.STRIP_METADATA -> "clean"
    ToolId.ENHANCE -> "enhance"; ToolId.APPLY_RECIPE -> "look"; ToolId.WATERMARK -> "watermark"; ToolId.PASSPORT -> "passport"
    ToolId.CUTOUT -> "cutout"; ToolId.SCAN -> "scan"; ToolId.SCAN_PDF -> "pdf"; else -> null
}

private fun adoptAndEdit(still: StillApplication, file: StillFile, scope: kotlinx.coroutines.CoroutineScope, open: (String) -> Unit) {
    scope.launch {
        val p = runCatching { still.store.adoptAsProject(file) }.getOrNull() ?: return@launch
        open(p.id)
    }
}
