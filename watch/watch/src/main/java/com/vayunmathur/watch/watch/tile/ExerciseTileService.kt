package com.vayunmathur.watch.watch.tile

import android.content.Context
import androidx.concurrent.futures.ResolvableFuture
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.TypeBuilders.StringLayoutConstraint
import androidx.wear.protolayout.TypeBuilders.StringProp
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInstant
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInt32
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicString
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.vayunmathur.watch.watch.MainActivity
import com.vayunmathur.watch.watch.service.ExerciseService
import com.vayunmathur.watch.watch.ui.ExerciseControlActivity
import java.time.Instant

/**
 * Quick-glance workout controls Tile. When no workout is active it shows a
 * "Start workout" chip that launches [MainActivity]'s picker; while a workout is
 * running it shows the elapsed time + live HR and Pause/Resume + Stop buttons.
 *
 * Tiles cannot call a Service directly, so the control buttons launch the
 * transparent [ExerciseControlActivity] trampoline, which forwards the action to
 * [ExerciseService]. The service calls TileService.getUpdater().requestUpdate()
 * on every state change; a modest freshness interval is a fallback.
 */
class ExerciseTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        val device = requestParams.deviceConfiguration
        val layout = buildLayout(this, device)
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(FRESHNESS_MS)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
        return immediate(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> {
        return immediate(
            ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build(),
        )
    }

    private fun buildLayout(context: Context, device: DeviceParameters): LayoutElement {
        val state = ExerciseService.uiState.value
        return if (state == ExerciseService.UiState.Idle || state == ExerciseService.UiState.Ended) {
            idleLayout(context, device)
        } else {
            activeLayout(context, device, state)
        }
    }

    private fun idleLayout(context: Context, device: DeviceParameters): LayoutElement {
        val launch = Clickable.Builder()
            .setId("start")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(context.packageName)
                            .setClassName(MainActivity::class.java.name)
                            .addKeyToExtraMapping(
                                MainActivity.EXTRA_OPEN_PICKER,
                                ActionBuilders.stringExtra("1"),
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()
        return PrimaryLayout.Builder(device)
            .setResponsiveContentInsetEnabled(true)
            .setContent(
                Text.Builder(context, "Start a workout")
                    .setTypography(Typography.TYPOGRAPHY_BODY1)
                    .setMaxLines(2)
                    .build(),
            )
            .setPrimaryChipContent(
                CompactChip.Builder(context, "Start", launch, device).build(),
            )
            .build()
    }

    private fun activeLayout(
        context: Context,
        device: DeviceParameters,
        state: ExerciseService.UiState,
    ): LayoutElement {
        val label = ExerciseService.activeWorkoutLabel.value ?: "Workout"
        val hr = ExerciseService.heartRate.value?.let { "HR ${it.toInt()}" } ?: "HR --"

        val paused = state == ExerciseService.UiState.Paused
        val pauseResumeAction = if (paused) ExerciseControlActivity.ACTION_RESUME
        else ExerciseControlActivity.ACTION_PAUSE
        val pauseResumeLabel = if (paused) "Resume" else "Pause"

        val controls = LayoutElementBuilders.Row.Builder()
            .addContent(
                Button.Builder(context, controlClickable("pauseResume", pauseResumeAction))
                    .setTextContent(if (paused) "R" else "P")
                    .build(),
            )
            .addContent(
                Button.Builder(context, controlClickable("stop", ExerciseControlActivity.ACTION_STOP))
                    .setTextContent("S")
                    .build(),
            )
            .build()

        val content = LayoutElementBuilders.Column.Builder()
            .addContent(
                Text.Builder(context, durationProp(), durationConstraint())
                    .setTypography(Typography.TYPOGRAPHY_DISPLAY2)
                    .build(),
            )
            .addContent(
                Text.Builder(context, hr)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .build(),
            )
            .addContent(controls)
            .build()

        return PrimaryLayout.Builder(device)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(
                Text.Builder(context, label)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .build(),
            )
            .setContent(content)
            .setSecondaryLabelTextContent(
                Text.Builder(context, pauseResumeLabel)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                    .build(),
            )
            .build()
    }

    private fun controlClickable(id: String, action: String): Clickable =
        Clickable.Builder()
            .setId(id)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(ExerciseControlActivity::class.java.name)
                            .addKeyToExtraMapping(
                                ExerciseControlActivity.EXTRA_ACTION,
                                ActionBuilders.stringExtra(action),
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()

    // A live-ticking m:ss timer bound to the session start via a ProtoLayout
    // dynamic expression, so the platform re-evaluates it every second without a
    // tile refresh. Falls back to a static 0:00 before the start anchor is set.
    private fun durationProp(): StringProp {
        val startMs = ExerciseService.activeStartEpochMs.value
        val fallback = formatDuration(ExerciseService.activeDurationMs.value)
        val builder = StringProp.Builder(fallback)
        if (startMs > 0L) {
            val elapsed = DynamicInstant.withSecondsPrecision(Instant.ofEpochMilli(startMs))
                .durationUntil(DynamicInstant.platformTimeWithSecondsPrecision())
            val secondsFormat = DynamicInt32.IntFormatter.Builder()
                .setMinIntegerDigits(2)
                .build()
            val dynamic: DynamicString = elapsed.minutesPart.format()
                .concat(DynamicString.constant(":"))
                .concat(elapsed.secondsPart.format(secondsFormat))
            builder.setDynamicValue(dynamic)
        }
        return builder.build()
    }

    private fun durationConstraint(): StringLayoutConstraint =
        StringLayoutConstraint.Builder("88:88").build()

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }

    private fun <T> immediate(value: T): ListenableFuture<T> =
        ResolvableFuture.create<T>().apply { set(value) }

    companion object {
        private const val RESOURCES_VERSION = "1"
        // Fallback refresh; live updates are driven by ExerciseService.requestUpdate().
        private const val FRESHNESS_MS = 10_000L
    }
}
