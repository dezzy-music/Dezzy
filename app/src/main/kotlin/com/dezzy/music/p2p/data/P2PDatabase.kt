/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.p2p.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Copia local de la red social P2P. Vive sólo en el almacenamiento privado de la app
 * (nadie más la puede leer) y se reconstruye desde los relés si se borra.
 */
@Entity(tableName = "p2p_message", indices = [Index("peer"), Index("createdAt")])
data class P2PMessageEntity(
    @androidx.room.PrimaryKey val id: String,
    val peer: String,
    val fromMe: Boolean,
    val content: String,
    val createdAt: Long,
    /** Canción adjunta como JSON (ver `P2PSong.toJson`). */
    val songJson: String? = null,
    /** Enlace de invitación a una sala «Juntos». */
    val juntosLink: String? = null,
)

@Entity(tableName = "p2p_profile")
data class P2PProfileEntity(
    @androidx.room.PrimaryKey val pubkey: String,
    val name: String,
    val about: String,
    val picture: String?,
    val isPrivate: Boolean,
    val updatedAt: Long,
    val banner: String? = null,
    val bannerVideo: String? = null,
)

/** Publicaciones de cuentas privadas (llegan cifradas sólo a los seguidores aprobados). */
@Entity(tableName = "p2p_private_post", indices = [Index("author")])
data class P2PPrivatePostEntity(
    @androidx.room.PrimaryKey val id: String,
    val author: String,
    val createdAt: Long,
    /** El rumor completo en JSON. */
    val json: String,
)

/** Reacciones y comentarios privados (sobre publicaciones de cuentas privadas). */
@Entity(tableName = "p2p_private_reaction", indices = [Index("postId")])
data class P2PPrivateReactionEntity(
    @androidx.room.PrimaryKey val id: String,
    val postId: String,
    val author: String,
    val createdAt: Long,
    /** "like" o "comment". */
    val type: String,
    val content: String,
    /** Comentario al que responde (sólo comentarios). */
    val replyTo: String? = null,
)

/** Solicitudes de seguimiento a cuentas privadas: entrantes (a mí) y salientes (mías). */
@Entity(tableName = "p2p_follow_request", primaryKeys = ["pubkey", "incoming"])
data class P2PFollowRequestEntity(
    val pubkey: String,
    val incoming: Boolean,
    /** "pending", "approved" o "rejected". */
    val status: String,
    val createdAt: Long,
)

@Entity(tableName = "p2p_chat_state")
data class P2PChatStateEntity(
    @androidx.room.PrimaryKey val peer: String,
    val lastReadAt: Long,
)

data class PeerUnread(val peer: String, val unread: Int)

@Dao
interface P2PDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: P2PMessageEntity): Long

    @Query("SELECT * FROM p2p_message WHERE peer = :peer ORDER BY createdAt ASC")
    fun messages(peer: String): Flow<List<P2PMessageEntity>>

    @Query(
        """SELECT m.* FROM p2p_message m
           JOIN (SELECT peer, MAX(createdAt) AS lastAt FROM p2p_message GROUP BY peer) l
           ON m.peer = l.peer AND m.createdAt = l.lastAt
           GROUP BY m.peer ORDER BY m.createdAt DESC""",
    )
    fun lastMessages(): Flow<List<P2PMessageEntity>>

    @Query(
        """SELECT m.peer AS peer, COUNT(*) AS unread FROM p2p_message m
           LEFT JOIN p2p_chat_state s ON s.peer = m.peer
           WHERE m.fromMe = 0 AND m.createdAt > IFNULL(s.lastReadAt, 0)
           GROUP BY m.peer""",
    )
    fun unreadCounts(): Flow<List<PeerUnread>>

    @Upsert
    suspend fun upsertChatState(state: P2PChatStateEntity)

    @Query("DELETE FROM p2p_message WHERE peer = :peer")
    suspend fun deleteConversation(peer: String)

    @Upsert
    suspend fun upsertProfile(profile: P2PProfileEntity)

    @Query("SELECT * FROM p2p_profile WHERE pubkey = :pubkey")
    suspend fun profile(pubkey: String): P2PProfileEntity?

    @Query("SELECT * FROM p2p_profile WHERE pubkey IN (:pubkeys)")
    suspend fun profiles(pubkeys: List<String>): List<P2PProfileEntity>

    @Query("SELECT * FROM p2p_profile WHERE pubkey IN (:pubkeys)")
    fun profilesFlow(pubkeys: List<String>): Flow<List<P2PProfileEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPrivatePost(post: P2PPrivatePostEntity)

    @Query("SELECT * FROM p2p_private_post WHERE author IN (:authors) ORDER BY createdAt DESC LIMIT :limit")
    suspend fun privatePosts(authors: List<String>, limit: Int): List<P2PPrivatePostEntity>

    @Query("SELECT * FROM p2p_private_post WHERE author = :author ORDER BY createdAt DESC LIMIT :limit")
    suspend fun privatePostsOf(author: String, limit: Int): List<P2PPrivatePostEntity>

    @Query("SELECT * FROM p2p_private_post WHERE id = :id")
    suspend fun privatePost(id: String): P2PPrivatePostEntity?

    @Query("DELETE FROM p2p_private_post WHERE id = :id")
    suspend fun deletePrivatePost(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPrivateReaction(reaction: P2PPrivateReactionEntity)

    @Query("SELECT * FROM p2p_private_reaction WHERE postId IN (:postIds)")
    suspend fun privateReactions(postIds: List<String>): List<P2PPrivateReactionEntity>

    @Upsert
    suspend fun upsertFollowRequest(request: P2PFollowRequestEntity)

    @Query("SELECT * FROM p2p_follow_request WHERE incoming = 1 AND status = 'pending' ORDER BY createdAt DESC")
    fun pendingIncomingRequests(): Flow<List<P2PFollowRequestEntity>>

    @Query("SELECT pubkey FROM p2p_follow_request WHERE incoming = 1 AND status = 'approved'")
    suspend fun approvedFollowers(): List<String>

    @Query("SELECT * FROM p2p_follow_request WHERE pubkey = :pubkey AND incoming = :incoming")
    suspend fun followRequest(pubkey: String, incoming: Boolean): P2PFollowRequestEntity?

    @Query("SELECT * FROM p2p_follow_request WHERE incoming = 0")
    fun outgoingRequests(): Flow<List<P2PFollowRequestEntity>>

    @Query("DELETE FROM p2p_message")
    suspend fun clearMessages()

    @Query("DELETE FROM p2p_private_post")
    suspend fun clearPrivatePosts()

    @Query("DELETE FROM p2p_private_reaction")
    suspend fun clearPrivateReactions()

    @Query("DELETE FROM p2p_follow_request")
    suspend fun clearFollowRequests()

    @Query("DELETE FROM p2p_chat_state")
    suspend fun clearChatState()
}

@Database(
    entities = [
        P2PMessageEntity::class,
        P2PProfileEntity::class,
        P2PPrivatePostEntity::class,
        P2PPrivateReactionEntity::class,
        P2PFollowRequestEntity::class,
        P2PChatStateEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class P2PDatabase : RoomDatabase() {
    abstract val dao: P2PDao

    companion object {
        @Volatile
        private var instance: P2PDatabase? = null

        /** v2: portada del perfil y respuestas a comentarios. Sólo añade columnas; los chats se conservan. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE p2p_profile ADD COLUMN banner TEXT")
                db.execSQL("ALTER TABLE p2p_private_reaction ADD COLUMN replyTo TEXT")
            }
        }

        /** v3: video en bucle de la portada. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE p2p_profile ADD COLUMN bannerVideo TEXT")
            }
        }

        fun get(context: Context): P2PDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, P2PDatabase::class.java, "dezzy_p2p.db")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
