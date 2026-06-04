package com.vayunmathur.calendar.data
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import androidx.core.database.getIntOrNull
import androidx.core.database.getStringOrNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.vayunmathur.calendar.util.RRule
import kotlinx.serialization.Serializable
import java.time.DateTimeException
import kotlin.time.Duration
import kotlin.time.Instant

@Serializable
data class Event(
    val id: Long?,
    val calendarID: Long,
    val title: String,
    val description: String,
    val location: String,
    val color: Int?,
    // start and end are utc
    val start: Long,
    val end: Long,
    val timezone: String = "UTC",
    val allDay: Boolean,
    val rrule: RRule?,
    val exdate: List<LocalDate> = emptyList()
) {

    val startDateTimeDisplay: LocalDateTime
        get() = Instant.fromEpochMilliseconds(start).toLocalDateTime(TimeZone.of(timezone))

    val endDateTimeDisplay: LocalDateTime
        get() = Instant.fromEpochMilliseconds(end).toLocalDateTime(TimeZone.of(timezone))

    companion object {
        fun getAllEvents(context: Context): List<Event> {
            val events = mutableListOf<Event>()

            val uri = CalendarContract.Events.CONTENT_URI
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.CALENDAR_ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.EVENT_COLOR,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.EVENT_TIMEZONE,
                CalendarContract.Events.DELETED,
                CalendarContract.Events.RRULE,
                CalendarContract.Events.DURATION,
                CalendarContract.Events.EXDATE
            )
            try {
                val cursor = context.contentResolver.query(uri, projection, null, null, null)
                cursor?.use {
                    while (it.moveToNext()) {
                        try {
                            val id = it.getLong(it.getColumnIndexOrThrow(CalendarContract.Events._ID))
                            val calendarID =
                                it.getLong(it.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_ID))
                            val title =
                                it.getString(it.getColumnIndexOrThrow(CalendarContract.Events.TITLE))
                            val description =
                                it.getStringOrNull(it.getColumnIndexOrThrow(CalendarContract.Events.DESCRIPTION))
                                    ?: ""
                            val location =
                                it.getStringOrNull(it.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION))
                                    ?: ""
                            val color =
                                it.getIntOrNull(it.getColumnIndexOrThrow(CalendarContract.Events.EVENT_COLOR))
                            val start =
                                it.getLong(it.getColumnIndexOrThrow(CalendarContract.Events.DTSTART))
                            var end = it.getLong(it.getColumnIndexOrThrow(CalendarContract.Events.DTEND))
                            val allDay =
                                it.getInt(it.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)) == 1
                            var timezone =
                                it.getStringOrNull(it.getColumnIndexOrThrow(CalendarContract.Events.EVENT_TIMEZONE)) ?: TimeZone.currentSystemDefault().id
                            try {
                                TimeZone.of(timezone)
                            } catch(_: DateTimeException) {
                                timezone = TimeZone.currentSystemDefault().id
                            }
                            val deleted =
                                it.getInt(it.getColumnIndexOrThrow(CalendarContract.Events.DELETED)) == 1
                            val rrule =
                                it.getStringOrNull(it.getColumnIndexOrThrow(CalendarContract.Events.RRULE))
                                    ?: ""
                            val duration = it.getStringOrNull(it.getColumnIndexOrThrow(CalendarContract.Events.DURATION))
                            val exdateStr = it.getStringOrNull(it.getColumnIndexOrThrow(CalendarContract.Events.EXDATE))


                            val durationMillis = duration?.let { duration -> try {Duration.parse(duration).inWholeMilliseconds } catch(_: Exception) {0} }

                            if(end == 0L && durationMillis != null) {
                                end = start + durationMillis
                            }

                            // Parse EXDATE field - comma-separated RFC 5545 dates (YYYYMMDD or YYYYMMDDTHHMMSSZ)
                            val exdate = exdateStr?.split(",")?.mapNotNull { dateStr ->
                                try {
                                    val cleanDate = dateStr.trim()
                                    if (cleanDate.length >= 8) {
                                        val year = cleanDate.substring(0, 4).toInt()
                                        val month = cleanDate.substring(4, 6).toInt()
                                        val day = cleanDate.substring(6, 8).toInt()
                                        LocalDate(year, month, day)
                                    } else null
                                } catch (_: Exception) {
                                    null
                                }
                            } ?: emptyList()

                            if (deleted) continue

                            if (title == null) continue
                            val event = Event(
                                id,
                                calendarID,
                                title,
                                description,
                                location,
                                color,
                                start,
                                end,
                                timezone,
                                allDay,
                                RRule.parse(rrule, TimeZone.of(timezone)),
                                exdate
                            )
                            events.add(event)
                        } catch (e: Exception) {
                            Log.e("Event", "Error constructing event from cursor", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Event", "Error querying events", e)
            }

            return events
        }
    }
}