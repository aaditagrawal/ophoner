package dev.ophoner.device

import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

class PhoneActionsTool(access: DeviceAccess) : DeviceTool(access, null) {
    override val definition = deviceTool("phone_actions", "Open Android actions for alarms, timers, navigation, dialing, SMS composition, and calendar insertion. Dial and compose do not place a call or send a message; the user completes them in the receiving app.", listOf("alarm", "timer", "navigate", "dial", "compose_sms", "calendar_event"), mapOf("hour" to "Alarm hour 0-23", "minute" to "Alarm minute 0-59", "seconds" to "Timer length 1-86400", "label" to "Alarm/timer/event title", "destination" to "Navigation destination/address", "phone" to "Phone number", "text" to "SMS body", "start_ms" to "Calendar event start", "end_ms" to "Calendar event end"))
    override suspend fun perform(args: JsonObject): String {
        val intent = when (args.required("action")) {
            "alarm" -> Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, args.number("hour").also { require(it in 0..23) }.toInt()).putExtra(AlarmClock.EXTRA_MINUTES, args.number("minute").also { require(it in 0..59) }.toInt()).putExtra(AlarmClock.EXTRA_MESSAGE, args.text("label").orEmpty()).putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            "timer" -> Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, args.number("seconds").also { require(it in 1..86400) }.toInt()).putExtra(AlarmClock.EXTRA_MESSAGE, args.text("label").orEmpty()).putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            "navigate" -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(args.required("destination"))}"))
            "dial" -> Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", args.required("phone"), null))
            "compose_sms" -> Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", args.required("phone"), null)).putExtra("sms_body", args.required("text"))
            "calendar_event" -> {
                val start = args.number("start_ms"); val end = args.number("end_ms"); require(end > start)
                Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, args.required("label")).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            }
            else -> error("Unknown action")
        }
        withContext(Dispatchers.Main) { access.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return "Opened Android action. Complete any confirmation in the receiving app."
    }
}
