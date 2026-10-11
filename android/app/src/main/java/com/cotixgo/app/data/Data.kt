package com.cotixgo.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

// Campos de sincronización futura: id global UUIDv4 + marcas UTC + pendingSync.
// La cola de operaciones se implementa en la fase de sincronización (F3).

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pendingSync: Boolean = true,
)

@Entity(tableName = "professional_profiles", indices = [Index("userId")])
data class ProfileEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val businessName: String,
    val description: String = "",
    val commercialInfo: String = "",
    val logoRef: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val pendingSync: Boolean = true,
)

@Entity(tableName = "clients", indices = [Index("userId")])
data class ClientEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val type: String,
    val name: String,
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val notes: String = "",
    val archived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val pendingSync: Boolean = true,
)

@Dao
interface UserDao {
    @Query("SELECT * FROM users LIMIT 1")
    fun observe(): Flow<UserEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(user: UserEntity)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM professional_profiles WHERE userId = :userId ORDER BY businessName")
    fun observeForUser(userId: String): Flow<List<ProfileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: ProfileEntity)
}

@Dao
interface ClientDao {
    @Query("SELECT * FROM clients WHERE userId = :userId ORDER BY name")
    fun observeForUser(userId: String): Flow<List<ClientEntity>>

    @Query("SELECT * FROM clients WHERE id = :id")
    fun observeOne(id: String): Flow<ClientEntity?>

    @Query("SELECT * FROM clients WHERE id = :id")
    suspend fun get(id: String): ClientEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(client: ClientEntity)
}

@Database(
    entities = [UserEntity::class, ProfileEntity::class, ClientEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun profileDao(): ProfileDao
    abstract fun clientDao(): ClientDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cotixgo.db",
                ).build().also { instance = it }
            }
    }
}
