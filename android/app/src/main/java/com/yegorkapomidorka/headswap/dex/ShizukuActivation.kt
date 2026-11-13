package com.yegorkapomidorka.headswap.dex

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.BuildConfig
import com.yegorkapomidorka.headswap.R
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Main-process coordinator. Shizuku supplies shell identity; only the existing bootstrap proves access. */
@SuppressLint("StaticFieldLeak") // app stores applicationContext only, never an Activity.
object ShizukuActivation {
    private const val PERMISSION_REQUEST = 7301
    private val main = Handler(Looper.getMainLooper())
    private val operationLock = Any()
    private var app: Context? = null
    private var waitingPermission = false // main thread only
    @Volatile private var cancelled = false
    @Volatile var active = false
        private set

    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == PERMISSION_REQUEST && waitingPermission && active) {
            waitingPermission = false
            val ctx = app!!
            if (result == PackageManager.PERMISSION_GRANTED) launch(ctx)
            else fail(ctx, R.string.shizuku_permission_denied)
        }
    }

    /** Does not request permission or start a service; safe for home-screen and recovery checks. */
    fun available(): Boolean = runCatching {
        Build.VERSION.SDK_INT >= ShizukuActivationPolicy.MIN_SDK && Shizuku.pingBinder() &&
            Shizuku.getVersion() >= ShizukuActivationPolicy.MIN_SERVER_VERSION &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED &&
            Shizuku.getUid() == ShizukuActivationPolicy.SHELL_UID
    }.getOrDefault(false)

    fun preferred(context: Context): Boolean =
        PhoneActivationState(context).backend == PhoneActivationState.BACKEND_SHIZUKU

    /** User action, always on the UI thread. No ADB pairing, notification or network permission needed. */
    fun start(context: Context) {
        if (PhoneActivation.busy || active || worker?.isAlive == true) return
        val ctx = context.applicationContext
        if (app == null) {
            app = ctx
            Shizuku.addRequestPermissionResultListener(permissionResult, main)
            Shizuku.addBinderDeadListener({
                if (active && waitingPermission) fail(ctx, R.string.shizuku_not_running)
            }, main)
        }
        active = true
        cancelled = false
        try {
            when {
                Build.VERSION.SDK_INT < ShizukuActivationPolicy.MIN_SDK -> fail(ctx, R.string.shizuku_android_required)
                !Shizuku.pingBinder() -> fail(ctx, R.string.shizuku_not_running)
                Shizuku.getVersion() < ShizukuActivationPolicy.MIN_SERVER_VERSION -> fail(ctx, R.string.shizuku_update_required)
                Shizuku.getUid() != ShizukuActivationPolicy.SHELL_UID -> fail(ctx, R.string.shizuku_adb_required)
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> {
                    if (Shizuku.shouldShowRequestPermissionRationale()) {
                        fail(ctx, R.string.shizuku_permission_denied)
                    } else {
                        waitingPermission = true
                        PhoneActivation.status = PhoneActivation.Status(PhoneActivation.Step.SHIZUKU_PERMISSION)
                        Shizuku.requestPermission(PERMISSION_REQUEST)
                    }
                }
                else -> launch(ctx)
            }
        } catch (_: Exception) {
            fail(ctx, R.string.shizuku_not_running)
        }
    }

    fun cancel() {
        cancelled = true
        waitingPermission = false
        worker?.interrupt()
        // A dispatched launcher is detached and can already be replacing this process. Stop waiting only.
        PhoneActivation.status = PhoneActivation.Status(PhoneActivation.Step.IDLE)
        // Keep active until the worker exits, so a second dispatch cannot race its cleanup.
        if (worker?.isAlive != true) active = false
    }

    @Volatile private var worker: Thread? = null
    private class ActivationConflict(val packageName: String) : Exception()

    private fun launch(ctx: Context) {
        PhoneActivation.status = PhoneActivation.Status(PhoneActivation.Step.STARTING)
        worker = thread(name = "headswap-shizuku-activate") {
            val state = PhoneActivationState(ctx)
            var dispatched = false
            try {
                try {
                    withService(ctx) { service ->
                        if (cancelled) return@withService
                        val conflict = service.conflictingActivation()
                        if (conflict.isNotEmpty()) throw ActivationConflict(conflict)
                        val nonce = PhoneActivationPolicy.newNonce()
                        val options = ActivationToken(ctx).options(DexPrefs(ctx).usbPlaybackAudio)
                        state.begin(nonce, PhoneActivationState.BACKEND_SHIZUKU)
                        // Commit before the remote call: instrumentation may kill us before the reply.
                        dispatched = true
                        check(service.activate(options.token, options.audio, nonce)) { "Dispatch unconfirmed" }
                    }
                } catch (e: RemoteException) {
                    if (!dispatched) throw e
                    // Losing the reply during bootstrap is inconclusive; wait for the real access proof.
                    AppLog.i("SHIZUKU activation reply lost after dispatch")
                }
                if (cancelled) return@thread
                val until = SystemClock.elapsedRealtime() + PhoneActivationPolicy.DEADLINE_MS
                while (!cancelled && SystemClock.elapsedRealtime() < until) {
                    if (DelegatedDesktopAccess.isReady(ctx)) {
                        state.clear()
                        PhoneActivation.status = PhoneActivation.Status(PhoneActivation.Step.IDLE)
                        return@thread
                    }
                    Thread.sleep(250)
                }
                if (!cancelled) {
                    readFailure(ctx, state)
                    fail(ctx, R.string.shizuku_activation_failed, state.failure)
                }
            } catch (e: ActivationConflict) {
                if (!cancelled) fail(ctx, R.string.shizuku_activation_conflict,
                    ctx.getString(R.string.shizuku_activation_conflict, e.packageName))
            } catch (e: Exception) {
                // Binder death after dispatch can be the expected process replacement. Keep its durable nonce.
                AppLog.i("SHIZUKU activation ${e.javaClass.simpleName} dispatched=$dispatched")
                if (!cancelled) fail(ctx, if (available()) R.string.shizuku_activation_failed else R.string.shizuku_not_running)
            } finally {
                active = false
            }
        }
    }

    /** Main-thread permission callbacks are deliberately never used for unattended recovery or log reads. */
    fun collectFailure(context: Context) {
        val ctx = context.applicationContext
        val state = PhoneActivationState(ctx)
        if (state.failureChecked) return
        state.failureChecked = true
        thread(name = "headswap-shizuku-log") { readFailure(ctx, state) }
    }

    private fun readFailure(ctx: Context, state: PhoneActivationState) {
        val nonce = state.nonce ?: return
        val failure = if (!available()) ctx.getString(R.string.shizuku_not_running) else runCatching {
            withService(ctx) { PhoneActivationPolicy.failureMessage(it.readActivationLog(nonce)) }
        }.getOrNull()
        // Don't attach an old attempt's log to a newer dispatch or a completed activation.
        if (state.nonce == nonce) {
            state.failure = failure
            state.failureChecked = true
        }
    }

    /** Called from VdmHostRestart's worker, never the UI thread. */
    internal fun restartHost(context: Context): Boolean =
        available() && withService(context.applicationContext) { it.restartHost() }

    private fun fail(ctx: Context, message: Int, detail: String? = null) {
        waitingPermission = false
        PhoneActivation.status = PhoneActivation.Status(PhoneActivation.Step.FAILED, detail = detail ?: ctx.getString(message))
        active = false
    }

    /** Bind callbacks run on main; blocking Binder work stays on our worker. Serialize helper lifetimes. */
    private fun <T> withService(ctx: Context, action: (IShizukuActivation) -> T): T = synchronized(operationLock) {
        check(Looper.myLooper() != Looper.getMainLooper())
        check(available()) { "Shizuku unavailable" }
        val args = Shizuku.UserServiceArgs(ComponentName(ctx, ShizukuActivationService::class.java))
            .tag("desktop-activation-${android.os.Process.myUid()}")
            .version(BuildConfig.VERSION_CODE)
            .processNameSuffix("shizuku_activation")
            // The child launcher detaches with setsid/nohup. The helper itself must not outlive its client.
            .daemon(false)
        val latch = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val remote = AtomicReference<IShizukuActivation?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (!closed.get()) remote.set(IShizukuActivation.Stub.asInterface(binder))
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { remote.set(null); latch.countDown() }
        }
        main.post {
            if (!closed.get()) runCatching { Shizuku.bindUserService(args, connection) }
                .onFailure { latch.countDown() }
        }
        try {
            check(latch.await(12, TimeUnit.SECONDS)) { "Shizuku service timed out" }
            action(checkNotNull(remote.get()) { "Shizuku service disconnected" })
        } finally {
            closed.set(true)
            main.post {
                runCatching { Shizuku.unbindUserService(args, connection, true) }
                runCatching { Shizuku.unbindUserService(args, connection, false) }
            }
        }
    }
}
