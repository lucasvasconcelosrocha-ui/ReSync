package com.resync.app.engine.provider

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.resync.app.data.model.ContactRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Provedor Universal de Backup e Restauração de Contatos.
 * Exporta contatos para os formatos padrão vCard (.vcf) e JSON estruturado.
 * Na restauração, previne duplicações checando números de telefone e nomes existentes.
 */
class ContactsBackupProvider(private val context: Context) {

    companion object {
        private const val TAG = "ContactsBackupProvider"
    }

    private val gson = Gson()

    /**
     * Extrai todos os contatos autorizados via ContactsContract.
     */
    suspend fun queryContacts(): List<ContactRecord> = withContext(Dispatchers.IO) {
        val contacts = mutableListOf<ContactRecord>()
        val cr = context.contentResolver

        try {
            val projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.PHOTO_URI
            )

            val cursor = cr.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
            )

            cursor?.use { c ->
                val idIdx = c.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIdx = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val photoIdx = c.getColumnIndex(ContactsContract.Contacts.PHOTO_URI)

                while (c.moveToNext()) {
                    val contactId = c.getString(idIdx) ?: continue
                    val displayName = c.getString(nameIdx) ?: "Sem Nome"
                    val photoUriStr = if (photoIdx >= 0) c.getString(photoIdx) else null

                    // Telefones
                    val phones = getPhonesForContact(cr, contactId)
                    // E-mails
                    val emails = getEmailsForContact(cr, contactId)
                    // Endereços
                    val postalAddresses = getAddressesForContact(cr, contactId)
                    // Organizações
                    val orgs = getOrgsForContact(cr, contactId)
                    // Notas
                    val notes = getNotesForContact(cr, contactId)
                    // Foto em Base64 se disponível
                    val photoBase64 = photoUriStr?.let { loadPhotoBase64(cr, it) } ?: ""

                    contacts.add(
                        ContactRecord(
                            id = contactId,
                            displayName = displayName,
                            phoneNumbers = phones,
                            emails = emails,
                            postalAddresses = postalAddresses,
                            organizations = orgs,
                            notes = notes,
                            photoBase64 = photoBase64
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao consultar contatos: ${e.message}", e)
        }

        contacts
    }

    private fun getPhonesForContact(cr: ContentResolver, contactId: String): List<String> {
        val list = mutableListOf<String>()
        try {
            cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId),
                null
            )?.use { c ->
                val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext()) {
                    c.getString(numIdx)?.takeIf { it.isNotBlank() }?.let { list.add(it.trim()) }
                }
            }
        } catch (_: Exception) {}
        return list.distinct()
    }

    private fun getEmailsForContact(cr: ContentResolver, contactId: String): List<String> {
        val list = mutableListOf<String>()
        try {
            cr.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                arrayOf(contactId),
                null
            )?.use { c ->
                val emailIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                while (c.moveToNext()) {
                    c.getString(emailIdx)?.takeIf { it.isNotBlank() }?.let { list.add(it.trim()) }
                }
            }
        } catch (_: Exception) {}
        return list.distinct()
    }

    private fun getAddressesForContact(cr: ContentResolver, contactId: String): List<String> {
        val list = mutableListOf<String>()
        try {
            cr.query(
                ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS),
                "${ContactsContract.CommonDataKinds.StructuredPostal.CONTACT_ID} = ?",
                arrayOf(contactId),
                null
            )?.use { c ->
                val idx = c.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS)
                while (c.moveToNext()) {
                    c.getString(idx)?.takeIf { it.isNotBlank() }?.let { list.add(it.trim()) }
                }
            }
        } catch (_: Exception) {}
        return list.distinct()
    }

    private fun getOrgsForContact(cr: ContentResolver, contactId: String): List<String> {
        val list = mutableListOf<String>()
        try {
            cr.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Organization.COMPANY),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE),
                null
            )?.use { c ->
                val idx = c.getColumnIndex(ContactsContract.CommonDataKinds.Organization.COMPANY)
                while (c.moveToNext()) {
                    c.getString(idx)?.takeIf { it.isNotBlank() }?.let { list.add(it.trim()) }
                }
            }
        } catch (_: Exception) {}
        return list.distinct()
    }

    private fun getNotesForContact(cr: ContentResolver, contactId: String): String {
        try {
            cr.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Note.NOTE),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE),
                null
            )?.use { c ->
                if (c.moveToNext()) {
                    return c.getString(c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Note.NOTE)) ?: ""
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun loadPhotoBase64(cr: ContentResolver, uriStr: String): String {
        return try {
            val uri = Uri.parse(uriStr)
            cr.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Gera formato vCard 3.0 para a lista de contatos.
     */
    fun exportToVCard(contacts: List<ContactRecord>): String {
        val sb = StringBuilder()
        for (c in contacts) {
            sb.append("BEGIN:VCARD\r\n")
            sb.append("VERSION:3.0\r\n")
            sb.append("FN:${c.displayName}\r\n")
            sb.append("N:;${c.displayName};;;\r\n")
            for (p in c.phoneNumbers) {
                sb.append("TEL;TYPE=CELL:${p}\r\n")
            }
            for (e in c.emails) {
                sb.append("EMAIL;TYPE=INTERNET:${e}\r\n")
            }
            for (a in c.postalAddresses) {
                sb.append("ADR:;;${a.replace(";", ",")};;;;\r\n")
            }
            for (org in c.organizations) {
                sb.append("ORG:${org}\r\n")
            }
            if (!c.notes.isNullOrBlank()) {
                sb.append("NOTE:${c.notes.replace("\n", "\\n")}\r\n")
            }
            sb.append("END:VCARD\r\n")
        }
        return sb.toString()
    }

    /**
     * Salva os contatos em arquivos .vcf e .json dentro do diretório de destino.
     */
    suspend fun backupContactsToDir(outputDir: File): Pair<File, File> = withContext(Dispatchers.IO) {
        outputDir.mkdirs()
        val contacts = queryContacts()

        // 1. Arquivo JSON estruturado
        val jsonFile = File(outputDir, "contacts.json")
        jsonFile.writeText(gson.toJson(contacts))

        // 2. Arquivo vCard universal
        val vcfFile = File(outputDir, "contacts.vcf")
        vcfFile.writeText(exportToVCard(contacts))

        Pair(jsonFile, vcfFile)
    }

    /**
     * Restaura contatos a partir de arquivo JSON estruturado com prevenção de duplicatas.
     */
    suspend fun restoreContactsFromJson(jsonFile: File): Int = withContext(Dispatchers.IO) {
        if (!jsonFile.exists() || !jsonFile.canRead()) return@withContext 0

        val type = object : TypeToken<List<ContactRecord>>() {}.type
        val toRestore: List<ContactRecord> = try {
            gson.fromJson(jsonFile.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao ler JSON de contatos para restauro: ${e.message}")
            emptyList()
        }

        if (toRestore.isEmpty()) return@withContext 0

        // Obter contatos existentes para evitar duplicidade
        val existingContacts = queryContacts()
        val existingPhones = existingContacts.flatMap { it.phoneNumbers }.map { cleanPhone(it) }.filter { it.isNotBlank() }.toSet()
        val existingNames = existingContacts.map { it.displayName.trim().lowercase() }.toSet()

        var insertedCount = 0
        val cr = context.contentResolver

        for (contact in toRestore) {
            // Verificar duplicidade por telefone
            val hasCommonPhone = contact.phoneNumbers.any { p ->
                val clean = cleanPhone(p)
                clean.isNotBlank() && existingPhones.contains(clean)
            }
            // Verificar duplicidade por nome exato
            val hasSameName = existingNames.contains(contact.displayName.trim().lowercase()) && contact.displayName.isNotBlank()

            if (hasCommonPhone || hasSameName) {
                // Já existe no dispositivo, pula para não duplicar
                continue
            }

            try {
                val ops = ArrayList<ContentProviderOperation>()
                val rawContactInsertIndex = ops.size

                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                        .build()
                )

                // Nome
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, contact.displayName)
                        .build()
                )

                // Telefones
                for (phone in contact.phoneNumbers) {
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                            .build()
                    )
                }

                // Emails
                for (email in contact.emails) {
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                            .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
                            .build()
                    )
                }

                // Endereços
                for (addr in contact.postalAddresses) {
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, addr)
                            .build()
                    )
                }

                // Organizações
                for (org in contact.organizations) {
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, org)
                            .build()
                    )
                }

                // Nota
                if (!contact.notes.isNullOrBlank()) {
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Note.NOTE, contact.notes)
                            .build()
                    )
                }

                cr.applyBatch(ContactsContract.AUTHORITY, ops)
                insertedCount++
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao restaurar contato '${contact.displayName}': ${e.message}")
            }
        }

        insertedCount
    }

    private fun cleanPhone(phone: String): String {
        return phone.replace(Regex("[^0-9+]"), "")
    }
}
