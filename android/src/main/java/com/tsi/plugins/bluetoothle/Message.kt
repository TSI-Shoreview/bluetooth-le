package com.tsi.plugins.bluetoothle

import androidx.room.*

@Entity
data class Message (
    @PrimaryKey
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM message")
    fun getAll(): List<Message>

    @Query("SELECT * FROM message WHERE timestamp < :endTime")
    fun getFrom(endTime: Long): List<Message>

    @Insert
    fun insertAll(vararg messages: Message)

    @Delete
    fun delete(message: Message)
}

@Database(entities = [Message::class], version = 1)
abstract class MessageDatabase : RoomDatabase() {
    abstract fun messageDao() : MessageDao
}