package com.vayunmathur.watch.watch.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.health.services.client.data.ExerciseState
import androidx.wear.tiles.TileService
import com.vayunmathur.watch.watch.R
import com.vayunmathur.watch.watch.data.SensorDatabase
import com.vayunmathur.watch.watch.data.WorkoutType
import com.vayunmathur.watch.watch.tile.ExerciseTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground service (type health, plus location for GPS workouts) that owns an
 * [ExerciseController] for one active workout. It exposes companion [StateFlow]s
 * consumed by the in-app screen and the Tile, and reacts to START/PAUSE/RESUME/
 * STOP actions delivered via [onStartCommand]. It also ensures
 * [SensorBackgroundService] (the BLE GATT server) is running so the ended-session
 * row can actually ship to the phone.
 */
class ExerciseService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var controller: ExerciseController? = null

    override fun onCreate() {
        super.onCreate()
        val dao = SensorDatabase.get(this).sensorDao()
        controller = ExerciseController(
            context = this,
            dao = dao,
            scope = scope,
            onState = { onExerciseState(it) },
            onMetrics = { m ->
                activeDurationMs.value = m.activeDurationMs
                heartRate.value = m.heartRateBpm
                calories.value = m.calories
                distance.value = m.distanceMeters
            },
            onMessage = { message.value = it },
            onAvailability = { availability.value = it.ifEmpty { null } },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra(EXTRA_ACTION) ?: ACTION_START
        val workout = intent?.getStringExtra(EXTRA_WORKOUT)
            ?.let { runCatching { WorkoutType.valueOf(it) }.getOrNull() }

        when (action) {
            ACTION_START -> {
                val type = workout ?: WorkoutType.Workout
                startForegroundFor(type)
                activeWorkoutLabel.value = type.label
                message.value = null
                // Ensure the BLE pipeline is up so the session row ships.
                SensorBackgroundService.start(this)
                controller?.start(type)
                requestTileUpdate()
            }
            ACTION_PAUSE -> controller?.pause()
            ACTION_RESUME -> controller?.resume()
            ACTION_STOP -> controller?.stop()
        }
        return START_STICKY
    }

    private fun onExerciseState(state: ExerciseState) {
        uiState.value = when {
            state.isEnded -> UiState.Ended
            state.isPaused -> UiState.Paused
            state == ExerciseState.PREPARING || state == ExerciseState.USER_STARTING -> UiState.Preparing
            else -> UiState.Active
        }
        // Anchor the live Tile timer once the session is genuinely active.
        if (uiState.value == UiState.Active && activeStartEpochMs.value == 0L) {
            activeStartEpochMs.value = System.currentTimeMillis() - activeDurationMs.value
        }
        requestTileUpdate()
        if (state.isEnded) {
            resetLiveState()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startForegroundFor(workout: WorkoutType) {
        val hasLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val type = if (workout.isGpsBased && hasLocation) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        }
        startForeground(NOTIFICATION_ID, buildNotification(), type)
    }

    private fun requestTileUpdate() {
        try {
            TileService.getUpdater(this).requestUpdate(ExerciseTileService::class.java)
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.exercise_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.exercise_notification_title))
            .setContentText(getString(R.string.exercise_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    private fun resetLiveState() {
        activeDurationMs.value = 0L
        heartRate.value = null
        calories.value = null
        distance.value = null
        activeWorkoutLabel.value = null
        activeStartEpochMs.value = 0L
        availability.value = null
    }

    override fun onDestroy() {
        scope.cancel()
        controller = null
        if (uiState.value != UiState.Ended) uiState.value = UiState.Idle
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    enum class UiState { Idle, Preparing, Active, Paused, Ended }

    companion object {
        private const val CHANNEL_ID = "exercise_session"
        private const val NOTIFICATION_ID = 2

        const val EXTRA_ACTION = "action"
        const val EXTRA_WORKOUT = "workout"
        const val ACTION_START = "START"
        const val ACTION_PAUSE = "PAUSE"
        const val ACTION_RESUME = "RESUME"
        const val ACTION_STOP = "STOP"

        val uiState = MutableStateFlow(UiState.Idle)
        val activeDurationMs = MutableStateFlow(0L)
        val heartRate = MutableStateFlow<Double?>(null)
        val calories = MutableStateFlow<Double?>(null)
        val distance = MutableStateFlow<Double?>(null)
        val activeWorkoutLabel = MutableStateFlow<String?>(null)
        val message = MutableStateFlow<String?>(null)
        // Epoch ms the active session started; drives the live Tile timer. 0 = idle.
        val activeStartEpochMs = MutableStateFlow(0L)
        // Non-null "Acquiring GPS/HR…" hint while a sensor is not yet available.
        val availability = MutableStateFlow<String?>(null)

        val uiStateFlow: StateFlow<UiState> = uiState
        val activeDurationFlow: StateFlow<Long> = activeDurationMs
        val heartRateFlow: StateFlow<Double?> = heartRate
        val caloriesFlow: StateFlow<Double?> = calories
        val distanceFlow: StateFlow<Double?> = distance
        val activeWorkoutLabelFlow: StateFlow<String?> = activeWorkoutLabel
        val messageFlow: StateFlow<String?> = message
        val activeStartEpochFlow: StateFlow<Long> = activeStartEpochMs
        val availabilityFlow: StateFlow<String?> = availability

        fun start(context: Context, workout: WorkoutType) {
            send(context, ACTION_START, workout)
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE, null)
        fun resume(context: Context) = send(context, ACTION_RESUME, null)
        fun stop(context: Context) = send(context, ACTION_STOP, null)

        private fun send(context: Context, action: String, workout: WorkoutType?) {
            val intent = Intent(context, ExerciseService::class.java).apply {
                putExtra(EXTRA_ACTION, action)
                workout?.let { putExtra(EXTRA_WORKOUT, it.name) }
            }
            context.startForegroundService(intent)
        }
    }
}
