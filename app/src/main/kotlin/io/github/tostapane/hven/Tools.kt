// Runs the model's tool calls as Android Intents: timer, alarm, calendar event.
// Each Intent opens the clock or calendar app, which shows the user what was set.
package io.github.tostapane.hven

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

object Tools {
    // An Intent ready to fire, and the line the chat shows for it.
    private class Action(val intent: Intent, val line: String)

    // Runs the call and returns the chat line: what was set, or why nothing was.
    fun run(context: Context, call: ModelConfig.ToolCall): String {
        val action = when (call.name) {
            "set_timer" -> timer(call.args)
            "set_alarm" -> alarm(call.args)
            "add_calendar_event" -> calendarEvent(call.args)
            else -> null
        } ?: return "Couldn't do that: the request had missing or invalid values."
        return try {
            context.startActivity(action.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            action.line
        } catch (e: ActivityNotFoundException) {
            "No app on this phone can handle it. ${action.line}"
        }
    }

    // Null if the duration is zero or longer than 24 h, the most AlarmClock accepts.
    private fun timer(args: Map<String, String>): Action? {
        val hours = args["hours"]?.toIntOrNull() ?: 0
        val minutes = args["minutes"]?.toIntOrNull() ?: 0
        val seconds = args["seconds"]?.toIntOrNull() ?: 0
        val total = hours * 3600 + minutes * 60 + seconds
        if (total !in 1..86400) return null
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, total)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        return Action(intent, "Timer: ${durationText(total)}")
    }

    // Null if hour or minute is out of range. Android rings at the next time the clock shows hh:mm.
    private fun alarm(args: Map<String, String>): Action? {
        val hour = args["hour"]?.toIntOrNull() ?: return null
        val minute = args["minute"]?.toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        return Action(intent, "Alarm: ${LocalTime.of(hour, minute)}")
    }

    // Null if the title is empty or the date or time isn't real (2026-02-30, 25:00).
    // Without a duration, the calendar app picks its own default length.
    private fun calendarEvent(args: Map<String, String>): Action? {
        val title = args["title"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val date = runCatching { LocalDate.parse(args["date"]) }.getOrNull() ?: return null
        val start = runCatching { LocalTime.parse(args["start"]) }.getOrNull() ?: return null
        val begin = date.atTime(start).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
        val minutes = args["duration_minutes"]?.toIntOrNull()
        if (minutes != null && minutes > 0) {
            intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + minutes * 60_000L)
        }
        return Action(intent, "Event: $title, $date $start")
    }

    // e.g. "1 h 30 min", "10 min", "45 s".
    private fun durationText(totalSeconds: Int): String =
        listOf(totalSeconds / 3600 to "h", totalSeconds % 3600 / 60 to "min", totalSeconds % 60 to "s")
            .filter { it.first > 0 }
            .joinToString(" ") { "${it.first} ${it.second}" }
}
