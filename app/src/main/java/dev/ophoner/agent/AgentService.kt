package dev.ophoner.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.ophoner.R
import dev.ophoner.MainActivity
import dev.ophoner.device.ActionApproval
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AgentService : Service() {
    @Inject lateinit var agentRunManager: AgentRunManager
    @Inject lateinit var actionApproval: ActionApproval
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        serviceScope.launch {
            combine(agentRunManager.runs, actionApproval.pending) { runs, approvals ->
                val run = runs.values.lastOrNull { it.isRunning }
                Triple(run?.statusLabel(), run?.streamingText?.takeLast(160), approvals.isNotEmpty())
            }.distinctUntilChanged().sample(300).collectLatest {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
            }
        }
        serviceScope.launch {
            agentRunManager.runs.collectLatest { runs ->
                if (runs.values.none { it.isRunning }) {
                    delay(2_000)
                    if (agentRunManager.runs.value.values.none { it.isRunning }) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopRuns()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) { stopRuns() }

    private fun stopRuns() {
        actionApproval.denyAll()
        agentRunManager.cancelAll()
        // Cancelling the runs also triggers their shell-process cancellation handlers.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        actionApproval.denyAll()
        agentRunManager.cancelAll()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Agent runs",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps active agent tasks running while Ophoner is in the background."
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val state = agentRunManager.runs.value.values.lastOrNull { it.isRunning }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("conversation_id", state?.conversationId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, AgentService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val needsApproval = actionApproval.pending.value.isNotEmpty()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Ophoner · ${state?.statusLabel() ?: "Starting"}")
            .setContentText(if (needsApproval) "An action needs approval. Open Ophoner to review." else state?.streamingText?.takeLast(160)?.ifBlank { null } ?: "Open the chat to follow along or send a follow-up.")
            .setContentIntent(open)
            .addAction(0, "Open", open)
            .addAction(0, "Stop all", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val ACTION_STOP = "dev.ophoner.STOP_AGENT_RUNS"
        private const val CHANNEL_ID = "agent_runs"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
