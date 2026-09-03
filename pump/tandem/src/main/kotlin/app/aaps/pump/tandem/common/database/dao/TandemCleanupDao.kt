package app.aaps.pump.tandem.common.database.dao

import androidx.room.Dao
import androidx.room.Query
import io.reactivex.rxjava3.core.Completable

@Dao
interface TandemCleanupDao {
    @Query("DELETE FROM history_records WHERE pumpTime < :cutoffMs")
    fun deleteOldHistory(cutoffMs: Long): Completable

    @Query("DELETE FROM qualifying_events WHERE dateTime < :cutoffMs")
    fun deleteOldQualifying(cutoffMs: Long): Completable
}