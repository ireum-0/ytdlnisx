package com.ireum.ytdl.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.ireum.ytdl.database.models.FormatBatchPayload

@Dao
interface FormatBatchPayloadDao {
    @Insert
    suspend fun insert(payload: FormatBatchPayload)

    @Query("SELECT * FROM format_batch_payloads WHERE handoffId=:handoffId")
    suspend fun get(handoffId: String): FormatBatchPayload?
    @Query("UPDATE format_batch_payloads SET authority=:authority WHERE handoffId=:handoffId")
    suspend fun updateAuthority(handoffId: String, authority: String): Int
}

