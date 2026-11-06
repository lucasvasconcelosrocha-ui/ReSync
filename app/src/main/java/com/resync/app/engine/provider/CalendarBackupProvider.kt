package com.resync.app.engine.provider

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.resync.app.data.model.CalendarRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Provedor Universal de Backup e Restauração de Calendários e Eventos.
 * Exporta para o padrão internacional iCalendar (.ics) e JSON estruturado.
 * Na restauração, identifica calendários graváveis do usuário e previne eventos duplicados.
 */
class CalendarBackupProvider(private val context: Context) {

    companion object {
        private const val TAG = "CalendarBackupProvider"
    }

    private val gson = Gson()

    /**
     * Consulta todos os eventos acessíveis via CalendarContract.
     */
    suspend fun queryEvents(): List<CalendarRecord> = withContext(Dispatchers.IO) {
        val events = mutableListOf<CalendarRecord>()
        val cr = context.contentResolver

        try {
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.CALENDAR_ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.EVENT_TIMEZONE,
                CalendarContract.Events.RRULE,
                CalendarContract.Events.CALENDAR_DISPLAY_NAME
            )

            val cursor = cr.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                null,
                null,
                "${CalendarContract.Events.DTSTART} ASC"
            )

            cursor?.use { c ->
                val idIdx = c.getColumnIndex(CalendarContract.Events._ID)
                val calIdIdx = c.getColumnIndex(CalendarContract.Events.CALENDAR_ID)
                val titleIdx = c.getColumnIndex(CalendarContract.Events.TITLE)
                val descIdx = c.getColumnIndex(CalendarContract.Events.DESCRIPTION)
                val locIdx = c.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
                val startIdx = c.getColumnIndex(CalendarContract.Events.DTSTART)
                val endIdx = c.getColumnIndex(CalendarContract.Events.DTEND)
                val allDayIdx = c.getColumnIndex(CalendarContract.Events.ALL_DAY)
                val tzIdx = c.getColumnIndex(CalendarContract.Events.EVENT_TIMEZONE)
                val rruleIdx = c.getColumnIndex(CalendarContract.Events.RRULE)
                val calNameIdx = c.getColumnIndex(CalendarContract.Events.CALENDAR_DISPLAY_NAME)

                while (c.moveToNext()) {
                    val eventId = c.getLong(idIdx)
                    val title = c.getString(titleIdx) ?: "Sem Título"
                    val desc = c.getString(descIdx) ?: ""
                    val loc = c.getString(locIdx) ?: ""
                    val start = c.getLong(startIdx)
                    val end = c.getLong(endIdx)
                    val allDay = if (allDayIdx >= 0) c.getInt(allDayIdx) == 1 else false
                    val tz = c.getString(tzIdx) ?: TimeZone.getDefault().id
                    val rrule = if (rruleIdx >= 0) c.getString(rruleIdx) ?: "" else ""
                    val calName = if (calNameIdx >= 0) c.getString(calNameIdx) ?: "Padrão" else "Padrão"

                    val reminders = getRemindersForEvent(cr, eventId)

                    events.add(
                        CalendarRecord(
                            id = eventId,
                            calendarDisplayName = calName,
                            title = title,
                            description = desc,
                            eventLocation = loc,
                            dtStart = start,
                            dtEnd = end,
                            allDay = allDay,
                            eventTimezone = tz,
                            rrule = rrule,
                            hasAlarm = reminders.isNotEmpty(),
                            reminders = reminders
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao consultar eventos de calendário: ${e.message}", e)
        }

        events
    }

    private fun getRemindersForEvent(cr: ContentResolver, eventId: Long): List<Int> {
        val list = mutableListOf<Int>()
        try {
            cr.query(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(CalendarContract.Reminders.MINUTES),
                "${CalendarContract.Reminders.EVENT_ID} = ?",
                arrayOf(eventId.toString()),
                null
            )?.use { c ->
                val minIdx = c.getColumnIndex(CalendarContract.Reminders.MINUTES)
                while (c.moveToNext()) {
                    list.add(c.getInt(minIdx))
                }
            }
        } catch (_: Exception) {}
        return list
    }

    /**
     * Exporta eventos para o formato RFC 5545 iCalendar (.ics).
     */
    fun exportToICalendar(events: List<CalendarRecord>): String {
        val sdf = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val sb = StringBuilder()
        sb.append("BEGIN:VCALENDAR\r\n")
        sb.append("VERSION:2.0\r\n")
        sb.append("PRODID:-//ReSync Universal Engine//Android//PT-BR\r\n")

        for (e in events) {
            sb.append("BEGIN:VEVENT\r\n")
            sb.append("UID:${e.id}-${e.dtStart}@resync.app\r\n")
            sb.append("SUMMARY:${escapeICalText(e.title)}\r\n")
            if (!e.description.isNullOrBlank()) {
                sb.append("DESCRIPTION:${escapeICalText(e.description)}\r\n")
            }
            if (!e.eventLocation.isNullOrBlank()) {
                sb.append("LOCATION:${escapeICalText(e.eventLocation)}\r\n")
            }
            if (e.dtStart > 0) {
                sb.append("DTSTART:${sdf.format(Date(e.dtStart))}\r\n")
            }
            if (e.dtEnd != null && e.dtEnd > 0) {
                sb.append("DTEND:${sdf.format(Date(e.dtEnd))}\r\n")
            }
            if (!e.rrule.isNullOrBlank()) {
                sb.append("RRULE:${e.rrule}\r\n")
            }
            for (reminder in e.reminders) {
                sb.append("BEGIN:VALARM\r\n")
                sb.append("ACTION:DISPLAY\r\n")
                sb.append("DESCRIPTION:Reminder\r\n")
                sb.append("TRIGGER:-PT${reminder}M\r\n")
                sb.append("END:VALARM\r\n")
            }
            sb.append("END:VEVENT\r\n")
        }

        sb.append("END:VCALENDAR\r\n")
        return sb.toString()
    }

    private fun escapeICalText(text: String): String {
        return text.replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\n", "\\n")
    }

    /**
     * Salva eventos nos formatos .ics e .json no diretório de destino.
     */
    suspend fun backupCalendarToDir(outputDir: File): Pair<File, File> = withContext(Dispatchers.IO) {
        outputDir.mkdirs()
        val events = queryEvents()

        val jsonFile = File(outputDir, "calendar.json")
        jsonFile.writeText(gson.toJson(events))

        val icsFile = File(outputDir, "calendar.ics")
        icsFile.writeText(exportToICalendar(events))

        Pair(jsonFile, icsFile)
    }

    /**
     * Localiza um calendário gravável existente ou retorna o ID do primeiro calendário gravável.
     */
    private fun getWritableCalendarId(cr: ContentResolver): Long? {
        return try {
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
            )
            val cursor = cr.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
                arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                null
            )
            cursor?.use { c ->
                if (c.moveToNext()) {
                    c.getLong(c.getColumnIndexOrThrow(CalendarContract.Calendars._ID))
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Restaura eventos a partir de arquivo JSON estruturado com prevenção de duplicatas.
     */
    suspend fun restoreCalendarFromJson(jsonFile: File): Int = withContext(Dispatchers.IO) {
        if (!jsonFile.exists() || !jsonFile.canRead()) return@withContext 0

        val type = object : TypeToken<List<CalendarRecord>>() {}.type
        val toRestore: List<CalendarRecord> = try {
            gson.fromJson(jsonFile.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao ler JSON de calendário para restauro: ${e.message}")
            emptyList()
        }

        if (toRestore.isEmpty()) return@withContext 0

        val cr = context.contentResolver
        val writableCalId = getWritableCalendarId(cr)
        if (writableCalId == null) {
            Log.w(TAG, "Nenhum calendário gravável encontrado no dispositivo para restauro.")
            return@withContext 0
        }

        // Consultar eventos existentes para deduplicação
        val existing = queryEvents()
        val existingKeys = existing.map { "${it.title.trim()}_${it.dtStart}_${it.dtEnd}" }.toSet()

        var inserted = 0
        for (event in toRestore) {
            val key = "${event.title.trim()}_${event.dtStart}_${event.dtEnd}"
            if (existingKeys.contains(key)) {
                // Evento já existente, pula para evitar duplicações
                continue
            }

            try {
                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, writableCalId)
                    put(CalendarContract.Events.TITLE, event.title)
                    put(CalendarContract.Events.DESCRIPTION, event.description ?: "")
                    put(CalendarContract.Events.EVENT_LOCATION, event.eventLocation ?: "")
                    put(CalendarContract.Events.DTSTART, event.dtStart)
                    if (event.dtEnd != null) {
                        put(CalendarContract.Events.DTEND, event.dtEnd)
                    }
                    put(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
                    put(CalendarContract.Events.EVENT_TIMEZONE, event.eventTimezone?.ifBlank { TimeZone.getDefault().id } ?: TimeZone.getDefault().id)
                    if (!event.rrule.isNullOrBlank()) {
                        put(CalendarContract.Events.RRULE, event.rrule)
                    }
                }

                val uri = cr.insert(CalendarContract.Events.CONTENT_URI, values)
                val newEventId = uri?.lastPathSegment?.toLongOrNull()

                if (newEventId != null && event.reminders.isNotEmpty()) {
                    for (reminderMin in event.reminders) {
                        val reminderValues = ContentValues().apply {
                            put(CalendarContract.Reminders.EVENT_ID, newEventId)
                            put(CalendarContract.Reminders.MINUTES, reminderMin)
                            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                        }
                        cr.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
                    }
                }
                inserted++
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao restaurar evento '${event.title}': ${e.message}")
            }
        }

        inserted
    }
}
