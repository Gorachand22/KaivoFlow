package com.kaivoflow.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.feature.canvas.CanvasScreen
import com.kaivoflow.feature.flows.FlowListScreen
import com.kaivoflow.feature.flows.RunHistoryScreen
import com.kaivoflow.feature.permissions.KaivoPermission
import com.kaivoflow.feature.permissions.PermissionCentreScreen
import com.kaivoflow.feature.settings.CredentialsScreen
import com.kaivoflow.feature.settings.PrivacyCentreScreen
import com.kaivoflow.feature.settings.SettingsScreen
import com.kaivoflow.core.nodes.TriggerSpecs
import com.kaivoflow.core.work.FlowScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The only Activity. Everything below is Compose.
 *
 * `MainActivity` also owns the share-sheet hand-off: text shared into KaivoFlow becomes the seed
 * item of a Share Spark Run, which is why the entry point is `singleTask`.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var scheduler: FlowScheduler

    private val dispatchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleShare(intent)
        setContent {
            KaivoTheme {
                Surface(color = KaivoTheme.colors.canvasVoid) {
                    KaivoNavHost()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** "Share to KaivoFlow" becomes the seed item of every enabled Share Spark. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        dispatchScope.launch {
            runCatching {
                scheduler.dispatchTrigger(
                    TriggerSpecs.SHARE,
                    mapOf("text" to text.take(4_000), "subject" to subject, "sharedAt" to System.currentTimeMillis()),
                )
            }
        }
    }
}

private object Routes {
    const val Flows = "flows"
    const val Canvas = "canvas/{flowId}"
    const val History = "history"
    const val Settings = "settings"
    const val Privacy = "privacy"
    const val Permissions = "permissions"
    const val Credentials = "credentials"

    fun canvas(flowId: String) = "canvas/$flowId"
}

@Composable
private fun KaivoNavHost(
    sharedText: String? = null,
    navController: NavHostController = rememberNavController(),
) {
    val motion = KaivoTheme.motion
    val duration = if (motion.reduceMotion) 0 else 260

    NavHost(
        navController = navController,
        startDestination = Routes.Flows,
        enterTransition = { slideInHorizontally(tween(duration)) { it / 6 } + fadeIn(tween(duration)) },
        exitTransition = { fadeOut(tween(duration)) },
        popEnterTransition = { fadeIn(tween(duration)) },
        popExitTransition = { slideOutHorizontally(tween(duration)) { it / 6 } + fadeOut(tween(duration)) },
    ) {
        composable(Routes.Flows) {
            FlowListScreen(
                onOpenFlow = { flowId -> navController.navigate(Routes.canvas(flowId)) },
                onOpenHistory = { navController.navigate(Routes.History) },
                onOpenSettings = { navController.navigate(Routes.Settings) },
                onOpenPrompt = { flowId -> navController.navigate(Routes.canvas(flowId)) },
            )
        }
        composable(Routes.History) {
            RunHistoryScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.Canvas,
            arguments = listOf(navArgument("flowId") { type = NavType.StringType }),
        ) {
            CanvasScreen(
                onBack = { navController.popBackStack() },
                onOpenRunHistory = { navController.navigate(Routes.History) },
                onOpenCredentials = { navController.navigate(Routes.Credentials) },
            )
        }
        composable(Routes.Settings) {
            val context = LocalContext.current
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenPrivacy = { navController.navigate(Routes.Privacy) },
                onOpenPermissions = { navController.navigate(Routes.Permissions) },
                onOpenCredentials = { navController.navigate(Routes.Credentials) },
                onOpenHistory = { navController.navigate(Routes.History) },
            )
        }
        composable(Routes.Privacy) {
            PrivacyCentreScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.Permissions) {
            val context = LocalContext.current
            PermissionCentreScreen(
                onBack = { navController.popBackStack() },
                onRequest = { permission -> request(context, permission) },
            )
        }
        composable(Routes.Credentials) {
            CredentialsScreen(onBack = { navController.popBackStack() })
        }
    }
}

/**
 * Routes a permission request to the right surface:
 * runtime permissions go through `ActivityResultContracts`, notification access and exact alarms go
 * to the matching Settings screen (there is no dialog for them, and pretending otherwise is a bug).
 */
private fun request(context: android.content.Context, permission: KaivoPermission) {
    val activity = context.findActivity()
    when (permission) {
        KaivoPermission.NotificationAccess ->
            context.startActivity(
                Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        KaivoPermission.ExactAlarms ->
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(android.net.Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        else -> {
            val manifestPermission = permission.manifestPermission
            if (manifestPermission != null && activity != null) {
                activity.requestPermissions(arrayOf(manifestPermission), 4_101)
            }
        }
    }
}

private fun android.content.Context.findActivity(): android.app.Activity? {
    var current = this
    while (current is android.content.ContextWrapper) {
        if (current is android.app.Activity) return current
        current = current.baseContext
    }
    return null
}
