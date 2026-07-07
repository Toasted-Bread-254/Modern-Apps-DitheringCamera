package com.vayunmathur.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Color
import android.provider.CalendarContract
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * Screenshot generator driven by `:calendar:metadata`. Seeds a local calendar with a few
 * events into the system CalendarProvider before launch, then captures the month view.
 */
@RunWith(AndroidJUnit4::class)
class MetadataScreenshots {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private val outDir: File by lazy {
        File(ctx.getExternalFilesDir(null), "metadata_screenshots").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun snap(index: Int) {
        val image = composeRule.onRoot().captureToImage()
        File(outDir, "$index.png").outputStream().use { out ->
            image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun syncAdapterUri(uri: android.net.Uri) = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, "Personal")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    private fun seedCalendar() {
        val resolver = ctx.contentResolver
        val calValues = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, "Personal")
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, "Personal")
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Personal")
            put(CalendarContract.Calendars.CALENDAR_COLOR, Color.parseColor("#4285F4"))
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, "Personal")
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        val calId = ContentUris.parseId(
            resolver.insert(syncAdapterUri(CalendarContract.Calendars.CONTENT_URI), calValues)!!
        )

        val tz = TimeZone.getDefault().id
        // (title, dayOffsetFromToday, startHour, durationHours, location)
        val events = listOf(
            Event("Team standup", 0, 9, 1, "Meeting Room B"),
            Event("Lunch with Alex", 0, 12, 1, "Cafe Rio"),
            Event("Dentist appointment", 1, 15, 1, "Downtown Dental"),
            Event("Yoga class", 2, 18, 1, "Studio 5"),
            Event("Project deadline", 3, 17, 1, ""),
            Event("Weekend hike", 5, 8, 4, "Trailhead"),
        )
        for (e in events) {
            val cal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, e.dayOffset)
                set(Calendar.HOUR_OF_DAY, e.startHour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val start = cal.timeInMillis
            val end = start + e.durationHours * 60L * 60L * 1000L
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, e.title)
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.EVENT_TIMEZONE, tz)
                if (e.location.isNotEmpty()) put(CalendarContract.Events.EVENT_LOCATION, e.location)
            }
            resolver.insert(CalendarContract.Events.CONTENT_URI, values)
        }
    }

    private data class Event(val title: String, val dayOffset: Int, val startHour: Int, val durationHours: Int, val location: String)

    @Test
    fun generateStoreScreenshots() {
        seedCalendar()
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(4000)
            snap(1)
        }
    }
}
