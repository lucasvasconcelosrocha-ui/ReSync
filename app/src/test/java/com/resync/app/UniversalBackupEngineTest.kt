package com.resync.app

import com.google.gson.Gson
import com.resync.app.data.model.BackupItemType
import com.resync.app.data.model.CalendarRecord
import com.resync.app.data.model.ContactRecord
import com.resync.app.engine.BackupIntegrityValidator
import com.resync.app.engine.BackupPermissionManager
import com.resync.app.engine.provider.CalendarBackupProvider
import com.resync.app.engine.provider.ContactsBackupProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class UniversalBackupEngineTest {

    private val gson = Gson()

    @Test
    fun testSha256CalculationMatchesExpected() {
        val testData = "ReSync 1.0.24 Universal Engine".toByteArray(Charsets.UTF_8)
        val hash = BackupIntegrityValidator.calculateBytesSha256(testData)
        assertNotNull(hash)
        assertEquals(64, hash.length)

        // Stream hashing deve ser idêntico
        val streamHash = BackupIntegrityValidator.calculateStreamSha256(ByteArrayInputStream(testData))
        assertEquals(hash, streamHash)
    }

    @Test
    fun testVCardExportFormatting() {
        val provider = ContactsBackupProvider(object : android.content.ContextWrapper(null) {})
        val contacts = listOf(
            ContactRecord(
                id = "1",
                displayName = "Antigravity Dev",
                phoneNumbers = listOf("+5511999998888"),
                emails = listOf("dev@resync.app"),
                postalAddresses = listOf("Av Paulista, 1000"),
                organizations = listOf("ReSync Labs"),
                notes = "Engenheiro Sênior"
            )
        )

        val vcard = provider.exportToVCard(contacts)
        assertTrue(vcard.contains("BEGIN:VCARD"))
        assertTrue(vcard.contains("FN:Antigravity Dev"))
        assertTrue(vcard.contains("TEL;TYPE=CELL:+5511999998888"))
        assertTrue(vcard.contains("EMAIL;TYPE=INTERNET:dev@resync.app"))
        assertTrue(vcard.contains("ORG:ReSync Labs"))
        assertTrue(vcard.contains("END:VCARD"))
    }

    @Test
    fun testICalendarExportFormatting() {
        val provider = CalendarBackupProvider(object : android.content.ContextWrapper(null) {})
        val events = listOf(
            CalendarRecord(
                id = 101L,
                calendarDisplayName = "Trabalho",
                title = "Deploy ReSync Universal Engine",
                description = "Entrega Prompt Mestre V2",
                eventLocation = "Android Lab",
                dtStart = 1730840400000L,
                dtEnd = 1730844000000L,
                allDay = false,
                reminders = listOf(15, 60)
            )
        )

        val ical = provider.exportToICalendar(events)
        assertTrue(ical.contains("BEGIN:VCALENDAR"))
        assertTrue(ical.contains("BEGIN:VEVENT"))
        assertTrue(ical.contains("SUMMARY:Deploy ReSync Universal Engine"))
        assertTrue(ical.contains("DESCRIPTION:Entrega Prompt Mestre V2"))
        assertTrue(ical.contains("LOCATION:Android Lab"))
        assertTrue(ical.contains("BEGIN:VALARM"))
        assertTrue(ical.contains("TRIGGER:-PT15M"))
        assertTrue(ical.contains("END:VCALENDAR"))
    }

    @Test
    fun testPermissionManagerCategoryMapping() {
        val manager = BackupPermissionManager(object : android.content.ContextWrapper(null) {})
        val needsAllFiles = manager.needsAllFilesAccess(listOf(BackupItemType.FOLDER))
        assertNotNull(needsAllFiles)
    }

    @Test
    fun testContactRecordJsonSerialization() {
        val contact = ContactRecord(
            id = "42",
            displayName = "Maria Souza",
            phoneNumbers = listOf("+5521988887777"),
            emails = listOf("maria@example.com"),
            postalAddresses = listOf("Rua das Flores, 50"),
            organizations = listOf("Empresa Teste"),
            notes = "Cliente VIP",
            photoBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        )

        val json = gson.toJson(contact)
        val deserialized = gson.fromJson(json, ContactRecord::class.java)

        assertEquals(contact.id, deserialized.id)
        assertEquals(contact.displayName, deserialized.displayName)
        assertEquals(contact.phoneNumbers, deserialized.phoneNumbers)
        assertEquals(contact.emails, deserialized.emails)
        assertEquals(contact.postalAddresses, deserialized.postalAddresses)
        assertEquals(contact.organizations, deserialized.organizations)
        assertEquals(contact.notes, deserialized.notes)
        assertEquals(contact.photoBase64, deserialized.photoBase64)
    }

    @Test
    fun testCalendarRecordJsonSerialization() {
        val event = CalendarRecord(
            id = 77L,
            calendarDisplayName = "Pessoal",
            title = "Reunião de Alinhamento",
            description = "Status do projeto",
            eventLocation = "Google Meet",
            dtStart = 1730800000000L,
            dtEnd = 1730803600000L,
            allDay = false,
            eventTimezone = "America/Sao_Paulo",
            rrule = "FREQ=WEEKLY;BYDAY=MO",
            reminders = listOf(10, 30)
        )

        val json = gson.toJson(event)
        val deserialized = gson.fromJson(json, CalendarRecord::class.java)

        assertEquals(event.id, deserialized.id)
        assertEquals(event.title, deserialized.title)
        assertEquals(event.dtStart, deserialized.dtStart)
        assertEquals(event.dtEnd, deserialized.dtEnd)
        assertEquals(event.rrule, deserialized.rrule)
        assertEquals(event.reminders, deserialized.reminders)
    }
}
