package io.raaz.messenger.data.repository

import io.raaz.messenger.data.db.dao.ContactDao
import io.raaz.messenger.data.db.dao.SessionDao
import io.raaz.messenger.data.model.Contact
import io.raaz.messenger.data.model.Session
import io.raaz.messenger.crypto.CryptoManager
import io.raaz.messenger.util.AppLogger

class ContactRepository(
    private val contactDao: ContactDao,
    private val sessionDao: SessionDao
) {
    private val TAG = "ContactRepo"

    fun getAll(): List<Contact> = contactDao.getAll()

    fun getById(id: String): Contact? = contactDao.getById(id)

    fun add(contact: Contact): Session {
        contactDao.insert(contact)
        AppLogger.i(TAG, "Added contact ${contact.displayName} (${contact.id})")

        // Auto-create a session for this contact
        val existingSession = sessionDao.getByContactId(contact.id)
        if (existingSession != null) return existingSession

        val session = Session(
            id = CryptoManager.generateSessionId(),
            contactId = contact.id,
            createdAt = System.currentTimeMillis()
        )
        sessionDao.insert(session)
        AppLogger.i(TAG, "Created session ${session.id} for contact ${contact.id}")
        return session
    }

    fun delete(id: String) {
        contactDao.delete(id)
        AppLogger.i(TAG, "Deleted contact $id")
    }

    fun getSessions(): List<Session> = sessionDao.getAllWithPreview()
}
