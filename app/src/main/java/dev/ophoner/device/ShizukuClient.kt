package dev.ophoner.device

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShizukuClient @Inject constructor(private val access: DeviceAccess) {
    private val lock = Mutex()
    private val execution = Mutex()
    @Volatile private var remote: IPrivilegedService? = null
    private var connection: ServiceConnection? = null
    private val args get() = Shizuku.UserServiceArgs(ComponentName(access.context, PrivilegedService::class.java))
        .daemon(false).processNameSuffix("device").debuggable(false).version(1)
    private suspend fun connect(): IPrivilegedService = lock.withLock {
        remote?.takeIf { it.asBinder().pingBinder() }?.let { return@withLock it }
        access.requireEnabled(DeviceCapability.SHIZUKU)
        check(access.status(DeviceCapability.SHIZUKU) == "Ready") { "Start Shizuku and authorize Ophoner first" }
        val ready = CompletableDeferred<IPrivilegedService>()
        val listener = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val service = IPrivilegedService.Stub.asInterface(binder)
                if (service != null) { remote = service; ready.complete(service) } else ready.completeExceptionally(IllegalStateException("Missing Shizuku service"))
            }
            override fun onServiceDisconnected(name: ComponentName?) { remote = null }
            override fun onBindingDied(name: ComponentName?) { remote = null }
        }
        connection?.let { runCatching { Shizuku.unbindUserService(args, it, false) } }
        connection = listener
        try {
            Shizuku.bindUserService(args, listener)
            withTimeoutOrNull(10_000) { ready.await() } ?: error("Shizuku service connection timed out. Restart Shizuku and try again.")
        } catch (e: Exception) {
            runCatching { Shizuku.unbindUserService(args, listener, true) }
            remote = null
            throw e
        }
    }
    suspend fun execute(command: String, timeoutMs: Long): Array<String> = execution.withLock {
        access.requireEnabled(DeviceCapability.SHIZUKU)
        val service = connect()
        coroutineScope {
            // Binder calls block, so send cancellation through a separate transaction.
            val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { if (!currentCoroutineContext().isActive) runCatching { service.cancelAll() } }
            }
            try { withContext(Dispatchers.IO) { service.execute(command, timeoutMs.coerceIn(100, 120_000)) } }
            finally { withContext(NonCancellable) { cancellation.cancelAndJoin() } }
        }
    }
    fun cancelAll() { remote?.let { runCatching { it.cancelAll() } } }
}
