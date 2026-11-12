package dev.codex.widget

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.work.Configuration
import dev.codex.widget.data.DeviceAuth
import dev.codex.widget.data.DirectHttp
import dev.codex.widget.data.InternalUsageSource
import dev.codex.widget.data.SecureStateStore
import dev.codex.widget.data.UsageRepository
import dev.codex.widget.data.LoginManager
import dev.codex.widget.data.LoginPhase
import dev.codex.widget.data.StateStore
import dev.codex.widget.data.HttpTransport
import dev.codex.widget.data.ClaudeAuth
import dev.codex.widget.data.ClaudeUsageSource
import dev.codex.widget.domain.UsageProvider
import dev.codex.widget.notify.CreditNotifications
import dev.codex.widget.sync.Scheduler
import dev.codex.widget.sync.LoginWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppGraph(context: Context, val store: StateStore = SecureStateStore(context), private val http: HttpTransport = DirectHttp()) {
    private val appContext = context.applicationContext
    @Volatile var appVisible = false
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val auth = DeviceAuth(http)
    val repository = UsageRepository(store, auth, {
        when (it.provider) {
            UsageProvider.CODEX -> InternalUsageSource(http, it)
            UsageProvider.CLAUDE -> ClaudeUsageSource(http, it)
        }
    })
    val login = LoginManager(repository, auth, claude = ClaudeAuth(http))
    val notifications = CreditNotifications(context)
    fun recoverLogin() = scope.launch {
        runCatching {
            login.recover()
            enqueueAuthorization()
        }
    }
    fun beginLogin(provider: UsageProvider = UsageProvider.CODEX) = scope.launch {
        runCatching { login.begin(provider); enqueueAuthorization() }
    }
    fun resumeLogin() = scope.launch {
        runCatching {
            enqueueAuthorization()
        }
    }
    private suspend fun enqueueAuthorization() {
        val view = login.view()
        if (view.phase == LoginPhase.EXCHANGING || (view.phase == LoginPhase.WAITING && view.provider == UsageProvider.CODEX))
            LoginWork.enqueue(appContext)
    }
    fun cancelLogin() = scope.launch {
        LoginWork.cancel(appContext)
        runCatching { login.cancel() }
    }
    fun signOut() = scope.launch {
        LoginWork.cancel(appContext)
        runCatching { login.signOut() }
        notifications.clear()
        Scheduler.cancelReminders(appContext)
        dev.codex.widget.widget.CodexWidgetProvider.renderAll(appContext)
    }
}

open class CodexApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()
    open val graph by lazy { AppGraph(this) }
    override fun onCreate() {
        super.onCreate()
        graph.notifications.createChannel()
        graph.recoverLogin()
        val activity = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: android.content.Intent) {
                if (intent.action == android.content.Intent.ACTION_USER_PRESENT || intent.action == android.content.Intent.ACTION_SCREEN_ON) {
                    dev.codex.widget.widget.CodexWidgetProvider.renderAll(context)
                    Scheduler.refresh(context)
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_USER_PRESENT); addAction(android.content.Intent.ACTION_SCREEN_ON)
        }
        androidx.core.content.ContextCompat.registerReceiver(this, activity, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        Scheduler.install(this)
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { Scheduler.refresh(this@CodexApplication) }
            })
    }
}

val Context.graph: AppGraph get() = (applicationContext as CodexApplication).graph
