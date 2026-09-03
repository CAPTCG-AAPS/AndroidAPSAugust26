package app.aaps.pump.tandem.common.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.aaps.pump.tandem.common.database.data.entity.TandemHistoryRecordEntity
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single

@Dao
abstract class TandemHistoryRecordDao {

    @Query("SELECT * from history_records WHERE pumpSerial=:serialNo AND pumpTime >= :since order by sequenceId desc")
    abstract fun allSinceWithSerial(serialNo: Int, since: Long): Single<List<TandemHistoryRecordEntity>>

    @Query("SELECT * from history_records WHERE pumpSerial=:serialNo AND pumpTime >= :since order by sequenceId desc")
    abstract fun allSinceWithSerialBlocking(serialNo: Int, since: Long): List<TandemHistoryRecordEntity>

    @Query("SELECT COUNT(*) FROM history_records")
    abstract fun getHistoryCount(): Single<Long>

    @Query("SELECT sequenceId FROM history_records WHERE pumpSerial=:serialNo ORDER BY sequenceId DESC LIMIT :limit")
    abstract fun getLatestSequenceIdsBlocking(serialNo: Int, limit: Int): List<Long>

    @Query("SELECT MAX(sequenceId) FROM history_records WHERE pumpSerial=:serialNo")
    abstract fun getMaxSequenceIdBlocking(serialNo: Int): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun saveBlocking(tandemHistoryRecordEntity: TandemHistoryRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun saveAll(list: List<TandemHistoryRecordEntity>): Completable

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun save(tandemHistoryRecordEntity: TandemHistoryRecordEntity): Completable

    @Delete
    abstract fun delete(tandemHistoryRecordEntity: TandemHistoryRecordEntity): Completable

}
