package com.vayunmathur.everysync.model

/** A contact resolved from a remote source (CardDAV vCard or Google People). */
data class RemoteContact(
    /** Stable remote identifier (vCard UID or People resourceName). */
    val uid: String,
    /** Server ETag for change detection, if known. */
    val etag: String? = null,
    val displayName: String = "",
    val prefix: String = "",
    val firstName: String = "",
    val middleName: String = "",
    val lastName: String = "",
    val suffix: String = "",
    val organization: String = "",
    val note: String = "",
    /** number -> type (ContactsContract.CommonDataKinds.Phone type int). */
    val phones: List<TypedValue> = emptyList(),
    val emails: List<TypedValue> = emptyList(),
    val addresses: List<TypedValue> = emptyList(),
    /** ISO-8601 birthday (yyyy-MM-dd) or null. */
    val birthday: String? = null,
    /** For CardDAV: the resource path on the server (collection-relative). */
    val href: String? = null,
    val deleted: Boolean = false,
)

data class TypedValue(val value: String, val type: Int)

/** A calendar collection (CalDAV collection or Google calendarList entry). */
data class RemoteCalendar(
    val id: String,
    val displayName: String,
    val color: Int? = null,
    /** CalDAV collection ctag for cheap change detection. */
    val ctag: String? = null,
    /** CalDAV collection URL. */
    val url: String? = null,
)

/** A calendar event resolved from a remote source. */
data class RemoteEvent(
    val uid: String,
    val etag: String? = null,
    val calendarId: String,
    val summary: String = "",
    val description: String = "",
    val location: String = "",
    val startMillis: Long = 0L,
    val endMillis: Long = 0L,
    val allDay: Boolean = false,
    val timezone: String = "UTC",
    /** Raw RRULE line (without the "RRULE:" prefix), or null. */
    val rrule: String? = null,
    val href: String? = null,
    val deleted: Boolean = false,
)

/** A health measurement destined for Health Connect. */
data class RemoteMeasurement(
    /** Provider-scoped stable id, used as Health Connect clientRecordId. */
    val clientRecordId: String,
    val type: MeasurementType,
    val value: Double,
    /** Secondary value (e.g. diastolic for blood pressure). */
    val value2: Double = 0.0,
    val timeMillis: Long,
)

enum class MeasurementType {
    WEIGHT,
    HEIGHT,
    BODY_FAT,
    HEART_RATE,
    RESTING_HEART_RATE,
    OXYGEN_SATURATION,
    STEPS,
}
