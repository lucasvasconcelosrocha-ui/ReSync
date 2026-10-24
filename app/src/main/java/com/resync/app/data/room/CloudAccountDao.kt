package com.resync.app.data.room

import androidx.room.*
import kotlinx.coroutines.flow.Flow

class MaxCloudAccountsExceededException(
    message: String = "Limite máximo de 5 contas do Google Drive atingido. Remova uma conta para adicionar outra."
) : Exception(message)

@Dao
interface CloudAccountDao {

    @Query("SELECT * FROM room_cloud_accounts ORDER BY addedAt ASC")
    fun getAllAccounts(): Flow<List<CloudAccount>>

    @Query("SELECT * FROM room_cloud_accounts ORDER BY addedAt ASC")
    suspend fun getAllAccountsList(): List<CloudAccount>

    @Query("SELECT * FROM room_cloud_accounts WHERE email = :email LIMIT 1")
    suspend fun getAccountByEmail(email: String): CloudAccount?

    @Query("SELECT * FROM room_cloud_accounts WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultAccount(): CloudAccount?

    @Query("SELECT COUNT(*) FROM room_cloud_accounts")
    suspend fun getAccountCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRaw(account: CloudAccount)

    @Update
    suspend fun update(account: CloudAccount)

    @Delete
    suspend fun delete(account: CloudAccount)

    @Query("DELETE FROM room_cloud_accounts WHERE email = :email")
    suspend fun deleteByEmail(email: String)

    @Query("UPDATE room_cloud_accounts SET isDefault = 0")
    suspend fun clearDefaultFlags()

    @Query("UPDATE room_cloud_accounts SET isDefault = 1 WHERE email = :email")
    suspend fun setDefaultAccount(email: String)

    @Transaction
    suspend fun insertValidated(account: CloudAccount) {
        val existing = getAccountByEmail(account.email)
        if (existing == null) {
            val count = getAccountCount()
            if (count >= 5) {
                throw MaxCloudAccountsExceededException()
            }
        }
        val isFirst = getAccountCount() == 0
        val toInsert = if (isFirst) account.copy(isDefault = true) else account
        if (toInsert.isDefault) {
            clearDefaultFlags()
        }
        insertRaw(toInsert)
    }

    @Transaction
    suspend fun setAccountAsDefault(email: String) {
        clearDefaultFlags()
        setDefaultAccount(email)
    }
}

