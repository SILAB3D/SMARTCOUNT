package com.silab.smartcount.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

enum class DetectedKind {
    BIZUM_SENT, BIZUM_RECEIVED,
    TRANSFER_SENT, TRANSFER_RECEIVED,
    CARD_SPEND,          // pago con tarjeta en un comercio
    CARD_ADJUSTMENT,     // ajuste de un pago anterior (típico en gasolineras)
    DIRECT_DEBIT,        // recibo domiciliado
    REFUND,              // devolución de un comercio
    JOINT_SPEND,         // gasto de otra persona en una cuenta conjunta
    JOINT_WITHDRAWAL,    // retirada de una cuenta conjunta
    JOINT_INCOME,        // ingreso de alguien en una cuenta conjunta
    SELF_TRANSFER,       // movimiento entre tus propias cuentas
    INCOME_OTHER,        // entra dinero, sin regla concreta que lo identifique
    SPEND_OTHER,         // sale dinero, sin regla concreta que lo identifique
    UNKNOWN;

    val isMoneyIn: Boolean
        get() = this == BIZUM_RECEIVED || this == TRANSFER_RECEIVED ||
            this == REFUND || this == JOINT_INCOME || this == INCOME_OTHER

    /** Cómo llamarlo en la interfaz. */
    val label: String
        get() = when (this) {
            BIZUM_RECEIVED -> "Bizum recibido"
            BIZUM_SENT -> "Bizum enviado"
            TRANSFER_RECEIVED -> "Transferencia recibida"
            TRANSFER_SENT -> "Transferencia enviada"
            CARD_SPEND -> "Pago con tarjeta"
            CARD_ADJUSTMENT -> "Pago ajustado"
            DIRECT_DEBIT -> "Recibo domiciliado"
            REFUND -> "Devolución"
            JOINT_SPEND -> "Gasto en cuenta conjunta"
            JOINT_WITHDRAWAL -> "Retirada de cuenta conjunta"
            JOINT_INCOME -> "Ingreso en cuenta conjunta"
            SELF_TRANSFER -> "Entre tus cuentas"
            INCOME_OTHER -> "Ingreso"
            SPEND_OTHER -> "Cargo"
            UNKNOWN -> "Movimiento"
        }
}

enum class InboxStatus { PENDING, PUSHED, IGNORED }

/**
 * ALTA: encajó una regla concreta. BAJA: solo se ha deducido la dirección del
 * dinero, así que la bandeja lo marca como "revisar".
 */
enum class Confidence { HIGH, LOW }

/**
 * Un movimiento detectado en una notificación bancaria, pendiente de que el
 * usuario lo confirme y lo asigne a un grupo. Nada se envía a Tricount sin
 * confirmación explícita.
 */
@Entity(tableName = "inbox")
data class InboxEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val detectedAt: Long,
    val sourcePackage: String,
    val bankLabel: String,
    val rawTitle: String,
    val rawText: String,
    val amount: Double?,
    val currency: String = "EUR",
    val counterparty: String?,
    /** Comercio, cuando lo hay: el título de la notificación suele traerlo. */
    val merchant: String? = null,
    val concept: String?,
    val kind: DetectedKind = DetectedKind.UNKNOWN,
    val confidence: Confidence = Confidence.LOW,
    val status: InboxStatus = InboxStatus.PENDING,
    /**
     * Calibración a mano: true = "esto sí es un movimiento bancario",
     * false = "esto no lo es", null = lo que haya decidido el parser.
     *
     * Existe porque el parser acierta mucho pero no siempre, y las dos
     * equivocaciones cuestan distinto: un aviso comercial colado entre los
     * movimientos se ignora de un toque, pero una nómina que el banco notifica
     * sin importe se pierde para siempre si la app no ofrece rescatarla. Al
     * dejar mover cada notificación de un grupo al otro, la bandeja deja de ser
     * una lista de resultados y pasa a ser el sitio donde se afina el sistema.
     */
    val userMovement: Boolean? = null,
    /** Rellenados al enviarlo a Tricount */
    val tricountId: Int? = null,
    val remoteTxId: Int? = null,
    /** Hash para evitar duplicados cuando el banco repite la notificación */
    val dedupeKey: String
) {
    /** Lo que dedujo el parser: hay importe y el tipo se reconoció. */
    val parsedAsMovement: Boolean
        get() = amount != null && kind != DetectedKind.UNKNOWN

    /** Lo que vale: tu criterio si lo has dado, y si no el del parser. */
    val isBankMovement: Boolean
        get() = userMovement ?: parsedAsMovement
}

@Dao
interface InboxDao {
    @Query("SELECT * FROM inbox ORDER BY detectedAt DESC")
    fun observeAll(): Flow<List<InboxEntry>>

    @Query("SELECT * FROM inbox WHERE status = :status ORDER BY detectedAt DESC")
    fun observeByStatus(status: InboxStatus): Flow<List<InboxEntry>>

    @Query("SELECT COUNT(*) FROM inbox WHERE dedupeKey = :key AND detectedAt > :since")
    suspend fun countRecentWithKey(key: String, since: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: InboxEntry): Long

    @Update
    suspend fun update(entry: InboxEntry)

    /**
     * Solo los que son movimientos: desde que la bandeja también recoge las
     * notificaciones que no lo son, contarlas todas inflaría la chapa con
     * avisos comerciales que nadie va a asignar.
     */
    @Query(
        "SELECT COUNT(*) FROM inbox WHERE status = 'PENDING' AND (" +
            "userMovement = 1 OR (userMovement IS NULL AND amount IS NOT NULL AND kind != 'UNKNOWN')" +
            ")"
    )
    suspend fun countPending(): Int

    @Query("SELECT * FROM inbox WHERE id = :id")
    suspend fun byId(id: Long): InboxEntry?

    @Query("DELETE FROM inbox WHERE id = :id")
    suspend fun delete(id: Long)
}

class Converters {
    @TypeConverter fun kindToString(v: DetectedKind): String = v.name
    @TypeConverter fun stringToKind(v: String): DetectedKind =
        runCatching { DetectedKind.valueOf(v) }.getOrDefault(DetectedKind.UNKNOWN)

    @TypeConverter fun confidenceToString(v: Confidence): String = v.name
    @TypeConverter fun stringToConfidence(v: String): Confidence =
        runCatching { Confidence.valueOf(v) }.getOrDefault(Confidence.LOW)

    @TypeConverter fun statusToString(v: InboxStatus): String = v.name
    @TypeConverter fun stringToStatus(v: String): InboxStatus =
        runCatching { InboxStatus.valueOf(v) }.getOrDefault(InboxStatus.PENDING)
}

@Database(entities = [InboxEntry::class], version = 4, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun inboxDao(): InboxDao
}
