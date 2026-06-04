package com.vayunmathur.calendar.util
import kotlinx.datetime.DayOfWeek
import kotlinx.serialization.Serializable

@Serializable
data class RecurrenceParams(
    val freq: String, // NONE, DAILY, WEEKLY, MONTHLY, YEARLY
    val interval: Int,
    val daysOfWeek: List<DayOfWeek> = emptyList(), // for weekly
    val monthlyType: Int = 0, // 0: by month day, 1: by weekday/weekindex
    val endCondition: RRule.EndCondition = RRule.EndCondition.Never,
    val byMonthDay: List<Int> = emptyList(),
    val byMonth: List<Int> = emptyList(),
    val bySetPos: List<Int> = emptyList(),
    val byYearDay: List<Int> = emptyList(),
    val byWeekNo: List<Int> = emptyList(),
    val wkst: DayOfWeek? = null
) {
    companion object {
        fun fromRRule(rr: RRule?): RecurrenceParams? {
            if (rr == null) return null
            return when (rr) {
                is RRule.EveryXDays -> RecurrenceParams(
                    "days", rr.days, emptyList(), 0, rr.endCondition,
                    rr.byMonthDay ?: emptyList(),
                    rr.byMonth ?: emptyList(),
                    rr.bySetPos ?: emptyList(),
                    rr.byYearDay ?: emptyList(),
                    rr.byWeekNo ?: emptyList(),
                    rr.wkst
                )
                is RRule.EveryXWeeks -> RecurrenceParams(
                    "weeks", rr.weeks, rr.daysOfWeek, 0, rr.endCondition,
                    rr.byMonthDay ?: emptyList(),
                    rr.byMonth ?: emptyList(),
                    rr.bySetPos ?: emptyList(),
                    rr.byYearDay ?: emptyList(),
                    rr.byWeekNo ?: emptyList(),
                    rr.wkst
                )
                is RRule.EveryXMonths -> RecurrenceParams(
                    "months", rr.months, emptyList(), rr.typeE, rr.endCondition,
                    rr.byMonthDay ?: emptyList(),
                    rr.byMonth ?: emptyList(),
                    rr.bySetPos ?: emptyList(),
                    rr.byYearDay ?: emptyList(),
                    rr.byWeekNo ?: emptyList(),
                    rr.wkst
                )
                is RRule.EveryXYears -> RecurrenceParams(
                    "years", rr.years, emptyList(), 0, rr.endCondition,
                    rr.byMonthDay ?: emptyList(),
                    rr.byMonth ?: emptyList(),
                    rr.bySetPos ?: emptyList(),
                    rr.byYearDay ?: emptyList(),
                    rr.byWeekNo ?: emptyList(),
                    rr.wkst
                )
            }
        }
    }
}
