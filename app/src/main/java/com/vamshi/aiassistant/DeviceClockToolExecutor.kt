package com.vamshi.aiassistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import java.util.Calendar
import org.json.JSONObject

object DeviceClockToolExecutor {
    fun execute(context: Context, toolName: String, argumentsJson: String): Result<Unit> =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val intent = when (toolName) {
                "set_alarm" -> setAlarmIntent(arguments)
                "start_timer" -> startTimerIntent(arguments)
                "show_alarms" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
                "show_timers" -> Intent(AlarmClock.ACTION_SHOW_TIMERS)
                "snooze_alarm" -> snoozeAlarmIntent(arguments)
                "dismiss_alarm" -> dismissAlarmIntent(arguments)
                "dismiss_expired_timers" -> Intent(AlarmClock.ACTION_DISMISS_TIMER)
                else -> error("Unsupported device clock tool: $toolName")
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                error("No installed Clock app supports this action")
            }
        }

    private fun setAlarmIntent(arguments: JSONObject) =
        Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, arguments.getInt("hour"))
            putExtra(AlarmClock.EXTRA_MINUTES, arguments.getInt("minute"))
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            arguments.optionalString("label")?.let {
                putExtra(AlarmClock.EXTRA_MESSAGE, it)
            }
            if (arguments.has("vibrate")) {
                putExtra(AlarmClock.EXTRA_VIBRATE, arguments.getBoolean("vibrate"))
            }
            if (arguments.optBoolean("silent", false)) {
                putExtra(AlarmClock.EXTRA_RINGTONE, AlarmClock.VALUE_RINGTONE_SILENT)
            }

            val repeatDays = arguments.optJSONArray("repeatDays")
            if (repeatDays != null && repeatDays.length() > 0) {
                val calendarDays = ArrayList<Int>(repeatDays.length())
                for (index in 0 until repeatDays.length()) {
                    calendarDays += dayOfWeek(repeatDays.getString(index))
                }
                putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, calendarDays)
            }
        }

    private fun startTimerIntent(arguments: JSONObject) =
        Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, arguments.getInt("durationSeconds"))
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            arguments.optionalString("label")?.let {
                putExtra(AlarmClock.EXTRA_MESSAGE, it)
            }
        }

    private fun snoozeAlarmIntent(arguments: JSONObject) =
        Intent(AlarmClock.ACTION_SNOOZE_ALARM).apply {
            if (arguments.has("durationMinutes")) {
                putExtra(
                    AlarmClock.EXTRA_ALARM_SNOOZE_DURATION,
                    arguments.getInt("durationMinutes")
                )
            }
        }

    private fun dismissAlarmIntent(arguments: JSONObject) =
        Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
            when (arguments.getString("mode")) {
                "next" -> putExtra(
                    AlarmClock.EXTRA_ALARM_SEARCH_MODE,
                    AlarmClock.ALARM_SEARCH_MODE_NEXT
                )
                "all" -> putExtra(
                    AlarmClock.EXTRA_ALARM_SEARCH_MODE,
                    AlarmClock.ALARM_SEARCH_MODE_ALL
                )
                "label" -> {
                    putExtra(
                        AlarmClock.EXTRA_ALARM_SEARCH_MODE,
                        AlarmClock.ALARM_SEARCH_MODE_LABEL
                    )
                    putExtra(AlarmClock.EXTRA_MESSAGE, arguments.getString("label"))
                }
                "time" -> {
                    val hour = arguments.getInt("hour")
                    putExtra(
                        AlarmClock.EXTRA_ALARM_SEARCH_MODE,
                        AlarmClock.ALARM_SEARCH_MODE_TIME
                    )
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, arguments.optInt("minute", 0))
                    putExtra(AlarmClock.EXTRA_IS_PM, hour >= 12)
                }
                else -> error("Unknown alarm search mode")
            }
        }

    private fun JSONObject.optionalString(name: String): String? =
        if (has(name) && !isNull(name)) getString(name).takeIf { it.isNotBlank() } else null

    private fun dayOfWeek(day: String): Int = when (day.lowercase()) {
        "sunday" -> Calendar.SUNDAY
        "monday" -> Calendar.MONDAY
        "tuesday" -> Calendar.TUESDAY
        "wednesday" -> Calendar.WEDNESDAY
        "thursday" -> Calendar.THURSDAY
        "friday" -> Calendar.FRIDAY
        "saturday" -> Calendar.SATURDAY
        else -> error("Unknown repeat day: $day")
    }
}
