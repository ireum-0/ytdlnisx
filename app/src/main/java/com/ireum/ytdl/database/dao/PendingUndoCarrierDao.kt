package com.ireum.ytdl.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ireum.ytdl.database.models.PendingUndoCarrier

@Dao
interface PendingUndoCarrierDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(carrier: PendingUndoCarrier): Long

    @Query("SELECT * FROM pending_undo_carriers WHERE token = :token LIMIT 1")
    suspend fun get(token: String): PendingUndoCarrier?

    @Query("SELECT * FROM pending_undo_carriers ORDER BY createdAt, token")
    suspend fun getAll(): List<PendingUndoCarrier>

    /** The first resolution wins; repeating that same intent is idempotent. */
    @Query(
        "UPDATE pending_undo_carriers SET resolutionIntent = :intent, " +
            "resolverGeneration = :resolverGeneration, updatedAt = :updatedAt " +
            "WHERE token = :token AND kind = :kind AND " +
            "(resolutionIntent = '' OR resolutionIntent = :intent)"
    )
    suspend fun recordResolution(
        token: String,
        kind: String,
        intent: String,
        resolverGeneration: Long,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE pending_undo_carriers SET resolverGeneration = :resolverGeneration, " +
            "updatedAt = :updatedAt WHERE token = :token AND kind = :kind AND " +
            "resolutionIntent = :intent"
    )
    suspend fun recordResolverGeneration(
        token: String,
        kind: String,
        intent: String,
        resolverGeneration: Long,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM pending_undo_carriers WHERE token = :token")
    suspend fun delete(token: String): Int
}
