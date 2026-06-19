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
            val base = RecurrenceParams(
                freq = "", interval = 0,
                endCondition = rr.endCondition,
                byMonthDay = rr.byMonthDay.orEmpty(),
                byMonth = rr.byMonth.orEmpty(),
                bySetPos = rr.bySetPos.orEmpty(),
                byYearDay = rr.byYearDay.orEmpty(),
                byWeekNo = rr.byWeekNo.orEmpty(),
                wkst = rr.wkst
            )
            return when (rr) {
                is RRule.EveryXDays -> base.copy(freq = "days", interval = rr.days)
                is RRule.EveryXWeeks -> base.copy(freq = "weeks", interval = rr.weeks, daysOfWeek = rr.daysOfWeek)
                is RRule.EveryXMonths -> base.copy(freq = "months", interval = rr.months, monthlyType = rr.typeE)
                is RRule.EveryXYears -> base.copy(freq = "years", interval = rr.years)
            }
        }
    }
}
