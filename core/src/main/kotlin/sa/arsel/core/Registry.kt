package sa.arsel.core

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import sa.arsel.core.internal.EventController
import sa.arsel.core.internal.ForegroundWatcher
import sa.arsel.core.internal.PushController
import sa.arsel.core.internal.SessionTracker
import sa.arsel.core.log.ArselLog
import sa.arsel.core.net.RequestQueue
import sa.arsel.core.state.StateManager
import sa.arsel.core.store.ArselStore

/**
 * Lightweight service locator (no Hilt/Koin forced on hosts) — the Klaviyo-style Registry.
 * Built once by [Arsel.initialize]. The API client itself is not held here: the durable queue
 * is drained by a WorkManager worker that rebuilds it from persisted config.
 */
internal object Registry {
    @Volatile
    var initialized: Boolean = false
        private set

    lateinit var appContext: Context
        private set
    lateinit var config: ArselConfig
        private set
    lateinit var log: ArselLog
        private set
    lateinit var store: ArselStore
        private set
    lateinit var state: StateManager
        private set
    lateinit var queue: RequestQueue
        private set
    lateinit var controller: PushController
        private set
    lateinit var events: EventController
        private set
    lateinit var sessions: SessionTracker
        private set
    lateinit var scope: CoroutineScope
        private set

    @Synchronized
    fun init(
        context: Context,
        cfg: ArselConfig,
    ) {
        if (initialized) {
            log.w("Arsel.initialize called more than once — ignoring.")
            return
        }
        appContext = context.applicationContext
        config = cfg
        log = ArselLog(cfg.logLevel)
        store = ArselStore(appContext)
        // Persist config so PushSyncWorker can rebuild the API client after a cold restart.
        store.baseUrl = cfg.baseUrl
        store.clientKey = cfg.clientKey
        store.networkTimeoutMs = cfg.networkTimeoutMs
        store.logLevel = cfg.logLevel
        state = StateManager(store)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        queue = RequestQueue(appContext, store)
        controller = PushController(appContext, cfg, state, queue, scope, log)
        events = EventController(state, store, queue::enqueue, log)
        sessions = SessionTracker(store, events)
        initialized = true
        watchForeground()
        // Opens the first session for a cold start, where no activity has been through
        // onActivityStarted yet. Idempotent: the watcher's own foreground is then a no-op.
        runCatching { sessions.onForeground() }
            .onFailure { log.w("session start failed", it) }
    }

    /**
     * Best-effort: a host whose application context is not an [Application] (a heavily wrapped
     * ContentProvider context, some instrumentation setups) simply loses permission polling rather
     * than the SDK.
     */
    private fun watchForeground() {
        val application = appContext as? Application
        if (application == null) {
            log.w("application context is not an Application — permission changes will not be polled")
            return
        }
        ForegroundWatcher(
            clock = System::currentTimeMillis,
            onForeground = {
                controller.syncDeviceState()
                runCatching { sessions.onForeground() }.onFailure { log.w("session start failed", it) }
            },
            onBackground = {
                runCatching { sessions.onBackground() }.onFailure { log.w("session end failed", it) }
            },
        ).attach(application)
    }
}
