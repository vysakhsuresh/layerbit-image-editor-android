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
import androidx.compose.runtime.collectAsState
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
    const val TOOL = "tool/{tool}"
    const val EDITOR = "editor?project={project}&uri={uri}&panel={panel}"

    fun tool(tool: ToolId) = "tool/${tool.name}"
    fun editor(project: String? = null, uri: String? = null, panel: ToolId.Panel = ToolId.Panel.CROP): String {
        val args = buildList {
            if (project != null) add("project=" + Uri.encode(project))
            if (uri != null) add("uri=" + Uri.encode(uri))
            add("panel=" + panel.name)
        }
        return "editor?" + args.joinToString("&")
    }
}

/**
 * Navigation. A picture arriving from outside - the picker, a share, "edit in" - goes straight
 * to the editor; several go to the tool that can take them all; a launcher shortcut lands on
 * its tool.
 */
@Composable
fun StudioApp(
    settings: Settings,
    onSettingsChange: ((Settings) -> Settings) -> Unit,
    /** Bumped when a single picture arrives from outside; the Uri is in [incomingUri]. */
    openTicket: Int,
    incomingUri: Uri?,
    /** A tool to go straight to, from a shortcut or a share of several pictures. */
    jumpToTool: ToolId?,
    onJumpHandled: () -> Unit,
    onPickPicture: () -> Unit
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val studio = context.applicationContext as StudioApplication
    val scope = rememberCoroutineScope()

    var projects by remember { mutableStateOf<List<ImageStore.Project>>(emptyList()) }
    var projectsStamp by remember { mutableLongStateOf(0L) }
    LaunchedEffect(projectsStamp) { projects = withContext(Dispatchers.IO) { studio.store.projects() } }
    var removing by remember { mutableStateOf<ImageStore.Project?>(null) }

    LaunchedEffect(openTicket) {
        if (openTicket > 0 && incomingUri != null) navController.navigate(Routes.editor(uri = incomingUri.toString())) { launchSingleTop = true }
    }
    LaunchedEffect(jumpToTool) {
        jumpToTool?.let { tool ->
            if (tool.mode == ToolId.Mode.EDITOR) {
                // The editor needs a picture: the home board's picker provides one.
                onPickPicture()
            } else navController.navigate(Routes.tool(tool)) { launchSingleTop = true }
            onJumpHandled()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(StudioColors.Background).windowInsetsPadding(WindowInsets.systemBars)) {
        NavHost(navController = navController, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                LaunchedEffect(Unit) { projectsStamp = System.nanoTime() }
                HomeScreen(
                    projects = projects,
                    thumbnail = { studio.store.thumbnail(it) },
                    favourites = settings.favouriteTools,
                    recentTools = settings.recentTools.mapNotNull { ToolId.byName(it) },
                    onOpenPicture = onPickPicture,
                    onOpenProject = { p -> navController.navigate(Routes.editor(project = p.id)) },
                    onForgetProject = { removing = it },
                    onTool = { tool ->
                        studio.settings.rememberTool(tool.name)
                        if (tool.mode == ToolId.Mode.BATCH) navController.navigate(Routes.tool(tool))
                        else if (projects.isNotEmpty()) navController.navigate(Routes.editor(project = projects.first().id, panel = tool.panel))
                        else onPickPicture()
                    },
                    onToggleFavourite = { studio.settings.toggleFavourite(it.name) },
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
                    onStripMetadata = { file -> studio.handoff.offer(listOf(Uri.fromFile(file.file))); navController.navigate(Routes.tool(ToolId.STRIP_METADATA)) }
                )
            }
            composable(Routes.TOOL, arguments = listOf(navArgument("tool") { type = NavType.StringType })) { entry ->
                val tool = ToolId.byName(entry.arguments?.getString("tool"))
                if (tool == null) { LaunchedEffect(Unit) { navController.popBackStack() }; return@composable }
                Column {
                    StudioTopBar(title = tool.title, subtitle = tool.summary, onBack = { navController.popBackStack() }, iconRes = iconFor(tool), accent = GroupAccents.of(tool.group))
                    ToolRoute(
                        tool = tool,
                        onOpenInEditor = { file: StudioFile ->
                            scope.launch {
                                val p = runCatching { studio.store.adoptAsProject(file) }.getOrNull() ?: return@launch
                                navController.navigate(Routes.editor(project = p.id))
                            }
                        },
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            composable(Routes.SETTINGS) {
                var workspaceBytes by remember { mutableLongStateOf(0L) }
                var projectBytes by remember { mutableLongStateOf(0L) }
                var count by remember { mutableStateOf(0) }
                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) { workspaceBytes = studio.workspace.sizeBytes(); projectBytes = studio.store.projectsBytes(); count = studio.store.projects().size }
                }
                Column {
                    StudioTopBar(title = stringResource(R.string.app_settings), onBack = { navController.popBackStack() })
                    SettingsScreen(
                        settings = settings, workspaceBytes = workspaceBytes, projectBytes = projectBytes, projectCount = count,
                        onChange = onSettingsChange,
                        onClearWorkspace = { studio.workspace.clear(); studio.store.clearImports(); workspaceBytes = 0 },
                        onClearProjects = {
                            scope.launch(Dispatchers.IO) { studio.store.projects().forEach { studio.store.remove(it) }; studio.store.clearThumbnails(); projectBytes = 0; count = 0; projectsStamp = System.nanoTime() }
                        }
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

        // The intro, once, and not over a picture someone just shared in.
        val arrivedWithSomething = openTicket > 0 || jumpToTool != null
        var showIntro by remember { mutableStateOf(!studio.prompts.introSeen) }
        if (showIntro && !arrivedWithSomething) {
            BackHandler { showIntro = false; studio.prompts.introSeen = true }
            IntroScreen(onDone = { studio.prompts.introSeen = true; showIntro = false })
        }
    }
}
