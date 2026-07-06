package com.vayunmathur.watch.watch.service

import android.app.NotificationManager
import android.content.Context

/**
 * Coordinates automatic Do-Not-Disturb between the workout and sleep triggers so
 * they don't fight. Only one trigger "owns" auto-DnD at a time; the first to
 * [engage] wins until it [release]s. The prior interruption filter is remembered
 * and restored on release, but only if the filter is still the value we applied —
 * a manual override in between therefore wins.
 *
 * Setting only changes the *local* filter; the DndController then propagates it to
 * the paired phone over BLE for free.
 */
object AutoDndManager {

    const val OWNER_WORKOUT = "workout"
    const val OWNER_SLEEP = "sleep"

    private var owner: String? = null
    private var priorFilter: Int = -1
    private var appliedFilter: Int = -1

    @Synchronized
    fun engage(context: Context, owner: String, filter: Int) {
        if (this.owner != null) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return
        priorFilter = nm.currentInterruptionFilter
        appliedFilter = filter
        this.owner = owner
        if (nm.currentInterruptionFilter != filter) {
            runCatching { nm.setInterruptionFilter(filter) }
        }
    }

    @Synchronized
    fun release(context: Context, owner: String) {
        if (this.owner != owner) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted && nm.currentInterruptionFilter == appliedFilter) {
            runCatching { nm.setInterruptionFilter(priorFilter) }
        }
        this.owner = null
        priorFilter = -1
        appliedFilter = -1
    }
}
