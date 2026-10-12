package dev.ophoner.device

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private fun Cursor.rows(limit: Int = 100): String {
    val rows = mutableListOf<JsonElement>()
    while (rows.size < limit && moveToNext()) rows.add(buildJsonObject { columnNames.forEachIndexed { index, key -> put(key, if (isNull(index)) null else getString(index)?.take(2000)) } })
    return buildJsonObject { put("items", JsonArray(rows)); put("truncated", !isAfterLast && moveToNext()) }.toString()
}

class ContactsTool(access: DeviceAccess) : DeviceTool(access, DeviceCapability.CONTACTS) {
    override val definition = deviceTool("contacts", "Search contacts and their phone numbers, create a contact, or update an existing contact's display name and phone number. Use contact_id from search. Updates affect the first matching name/phone record.", listOf("search", "create", "update"), mapOf("query" to "Name or phone search", "contact_id" to "Contact ID for update", "name" to "Display name", "phone" to "Phone number"))
    override suspend fun perform(args: JsonObject): String {
        val resolver = access.context.contentResolver
        val action = args.required("action")
        if (action == "search") {
            check(access.granted(Manifest.permission.READ_CONTACTS)) { "Grant contacts read permission" }
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val q = "%${args.text("query").orEmpty()}%"
            return resolver.query(uri, arrayOf("contact_id", "display_name", "data1"), "display_name LIKE ? OR data1 LIKE ?", arrayOf(q, q), "display_name ASC")?.use { it.rows() } ?: "No contacts"
        }
        check(access.granted(Manifest.permission.WRITE_CONTACTS)) { "Grant contacts write permission" }
        val operations = arrayListOf<ContentProviderOperation>()
        val data = ContactsContract.Data.CONTENT_URI
        val nameType = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
        val phoneType = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
        when (action) {
            "create" -> {
                operations.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI).withValue("account_type", null).withValue("account_name", null).build())
                operations.add(ContentProviderOperation.newInsert(data).withValueBackReference("raw_contact_id", 0).withValue("mimetype", nameType).withValue("data1", args.required("name")).build())
                args.text("phone")?.let { operations.add(ContentProviderOperation.newInsert(data).withValueBackReference("raw_contact_id", 0).withValue("mimetype", phoneType).withValue("data1", it).withValue("data2", 2).build()) }
            }
            "update" -> {
                check(access.granted(Manifest.permission.READ_CONTACTS)) { "Grant contacts read permission" }
                val id = args.number("contact_id").also { require(it > 0) }.toString()
                require(args.text("name") != null || args.text("phone") != null) { "Provide name or phone" }
                for ((key, mime) in listOf("name" to nameType, "phone" to phoneType)) {
                    val value = args.text(key) ?: continue
                    val row = resolver.query(data, arrayOf("_id"), "contact_id=? AND mimetype=?", arrayOf(id, mime), null)?.use { if (it.moveToFirst()) it.getLong(0) else null }
                    if (row != null) operations.add(ContentProviderOperation.newUpdate(ContentUris.withAppendedId(data, row)).withValue("data1", value).build())
                    else {
                        val raw = resolver.query(ContactsContract.RawContacts.CONTENT_URI, arrayOf("_id"), "contact_id=? AND deleted=0", arrayOf(id), null)?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: error("Contact not found")
                        operations.add(ContentProviderOperation.newInsert(data).withValue("raw_contact_id", raw).withValue("mimetype", mime).withValue("data1", value).build())
                    }
                }
            }
            else -> error("Unknown action")
        }
        return resolver.applyBatch(ContactsContract.AUTHORITY, operations).joinToString("\n") { "uri=${it.uri}, updated=${it.count}" }
    }
}

class CalendarTool(access: DeviceAccess) : DeviceTool(access, DeviceCapability.CALENDAR) {
    override val definition = deviceTool("calendar", "List calendars, read event instances in a time range, or create/update/delete an event. Times are Unix milliseconds. List calendars to find a writable calendar_id. Recurring-event update/delete affects the entire event series.", listOf("calendars", "events", "create", "update", "delete"), mapOf("start_ms" to "Start time (or query start)", "end_ms" to "End time (or query end), maximum query span 31 days", "calendar_id" to "Writable calendar ID", "event_id" to "Event ID", "title" to "Event title", "description" to "Description", "location" to "Event location", "timezone" to "IANA timezone, defaults to device timezone"))
    override suspend fun perform(args: JsonObject): String {
        val resolver = access.context.contentResolver
        val action = args.required("action")
        if (action in setOf("calendars", "events")) {
            check(access.granted(Manifest.permission.READ_CALENDAR)) { "Grant calendar read permission" }
            if (action == "calendars") return resolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf("_id", "calendar_displayName", "calendar_access_level", "account_name"), null, null, null)?.use { it.rows() } ?: "No calendars"
            val start = args.number("start_ms"); val end = args.number("end_ms")
            require(start >= 0 && end > start && end - start <= 31L * 86_400_000) { "Query range must be between 0 and 31 days" }
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, start); ContentUris.appendId(builder, end)
            return resolver.query(builder.build(), arrayOf("event_id", "title", "begin", "end", "eventLocation", "calendar_id"), null, null, "begin ASC")?.use { it.rows() } ?: "No events"
        }
        check(access.granted(Manifest.permission.WRITE_CALENDAR)) { "Grant calendar write permission" }
        val values = ContentValues()
        if (action == "create" || args.text("start_ms") != null || args.text("end_ms") != null) {
            val start = args.number("start_ms"); val end = args.number("end_ms")
            require(start >= 0 && end > start) { "End must be after start" }
            values.put(CalendarContract.Events.DTSTART, start); values.put(CalendarContract.Events.DTEND, end)
        }
        args.text("title")?.let { values.put(CalendarContract.Events.TITLE, it) }
        args.text("description")?.let { values.put(CalendarContract.Events.DESCRIPTION, it) }
        args.text("location")?.let { values.put(CalendarContract.Events.EVENT_LOCATION, it) }
        args.text("timezone")?.let { require(it in TimeZone.getAvailableIDs()) { "Unknown timezone" }; values.put(CalendarContract.Events.EVENT_TIMEZONE, it) }
        val uri = CalendarContract.Events.CONTENT_URI
        return when (action) {
            "create" -> {
                values.put(CalendarContract.Events.TITLE, args.required("title"))
                values.put(CalendarContract.Events.CALENDAR_ID, args.number("calendar_id").also { require(it > 0) })
                if (!values.containsKey(CalendarContract.Events.EVENT_TIMEZONE)) values.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                "Created ${resolver.insert(uri, values) ?: error("Calendar rejected event") }"
            }
            "update" -> { require(values.size() > 0) { "Provide fields to update" }; val count = resolver.update(ContentUris.withAppendedId(uri, args.number("event_id").also { require(it > 0) }), values, null, null); check(count > 0) { "Event not found" }; "Updated $count event(s)" }
            "delete" -> { val count = resolver.delete(ContentUris.withAppendedId(uri, args.number("event_id").also { require(it > 0) }), null, null); check(count > 0) { "Event not found" }; "Deleted $count event(s)" }
            else -> error("Unknown action")
        }
    }
}

class LocationTool(access: DeviceAccess) : DeviceTool(access, DeviceCapability.LOCATION) {
    override val definition = deviceTool("location", "Request current location with a 20-second timeout. Keep Ophoner visible. Approximate permission returns approximate location. Includes accuracy and timestamp; do not claim a stale fix is current.", listOf("current"))
    @Suppress("MissingPermission", "DEPRECATION")
    override suspend fun perform(args: JsonObject): String {
        require(args.required("action") == "current")
        check(access.granted(Manifest.permission.ACCESS_COARSE_LOCATION)) { "Grant location permission" }
        val manager = access.context.getSystemService(LocationManager::class.java)
        val provider = if (access.granted(Manifest.permission.ACCESS_FINE_LOCATION) && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        check(manager.isProviderEnabled(provider)) { "Enable device location services" }
        val location = withContext(Dispatchers.Main) { withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine<Location> { continuation ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    manager.getCurrentLocation(provider, signal, access.context.mainExecutor) { result ->
                        if (continuation.isActive) { if (result != null) continuation.resume(result) else continuation.resumeWithException(IllegalStateException("No location fix available")) }
                    }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) { manager.removeUpdates(this); if (continuation.isActive) continuation.resume(location) }
                        override fun onProviderEnabled(provider: String) = Unit
                        override fun onProviderDisabled(provider: String) = Unit
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                    }
                    continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            }
        } } ?: error("Location timed out. Keep Ophoner visible and check device location settings.")
        return buildJsonObject { put("latitude", location.latitude); put("longitude", location.longitude); put("accuracy_meters", location.accuracy); put("timestamp_ms", location.time); put("provider", location.provider) }.toString()
    }
}

class MediaTool(access: DeviceAccess) : DeviceTool(access, DeviceCapability.MEDIA) {
    override val definition = deviceTool("media", "Search shared images, video, or audio by filename, and open an item. Visibility follows granted media permissions. Use shared_files for file organization when all-files access is enabled.", listOf("search", "open"), mapOf("kind" to "images, video, or audio", "query" to "Filename search", "id" to "Media ID returned by search"))
    override suspend fun perform(args: JsonObject): String {
        val kind = args.required("kind")
        val uri = when (kind) { "images" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI; "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI; "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI; else -> error("Unknown media kind") }
        val action = args.required("action")
        if (action == "search") return access.context.contentResolver.query(uri, arrayOf("_id", "_display_name", "mime_type", "_size", "date_modified"), "_display_name LIKE ?", arrayOf("%${args.text("query").orEmpty()}%"), "date_modified DESC")?.use { it.rows() } ?: "No media"
        require(action == "open")
        val item = ContentUris.withAppendedId(uri, args.number("id").also { require(it > 0) })
        val mime = access.context.contentResolver.getType(item) ?: error("Media not found")
        withContext(Dispatchers.Main) { access.context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(item, mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        return "Opened media viewer"
    }
}
