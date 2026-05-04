package com.vayunmathur.library.util

import android.content.Context
import java.io.File
import java.io.FileInputStream
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.InvalidationTracker
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.Json
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.max
import kotlin.math.min
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import androidx.core.content.edit


class DatabaseViewModel(val database: RoomDatabase, vararg daos: Pair<KClass<*>, TrueDao<*>>, val matchingDao: MatchingDao? = null) : ViewModel() {
    val daos = daos.associate { it.first to it.second }

    suspend inline fun <reified A: DatabaseItem, reified B: DatabaseItem> addPairs(pairs: List<Pair<A, B>>) {
        val classAIndex = daos.keys.indexOf(A::class)
        val classBIndex = daos.keys.indexOf(B::class)
        val type = min(classAIndex, classBIndex) + 100 * max(classAIndex, classBIndex)
        val orderedPairs = if(classAIndex < classBIndex) pairs else pairs.map { it.second to it.first }
        matchingDao!!.upsert(orderedPairs.map { (a, b) -> ManyManyMatching(a.id, b.id, type) })
    }

    inline fun <reified A: DatabaseItem, reified B: DatabaseItem> addPairsAsync(pairs: List<Pair<A, B>>) {
        viewModelScope.launch {
            addPairs(pairs)
        }
    }

    suspend inline fun <reified A: DatabaseItem, reified B: DatabaseItem> clearMatchings() {
        val classAIndex = daos.keys.indexOf(A::class)
        val classBIndex = daos.keys.indexOf(B::class)
        val type = min(classAIndex, classBIndex) + 100 * max(classAIndex, classBIndex)
        matchingDao!!.deleteByType(type)
    }

    inline fun <reified A: DatabaseItem, reified B: DatabaseItem> clearMatchingsAsync() {
        viewModelScope.launch {
            clearMatchings<A, B>()
        }
    }

    suspend inline fun <reified A: DatabaseItem, reified B: DatabaseItem> getMatches(a: Long): List<Long> {
        val classAIndex = daos.keys.indexOf(A::class)
        val classBIndex = daos.keys.indexOf(B::class)
        val type = min(classAIndex, classBIndex) + 100 * max(classAIndex, classBIndex)
        val ids = if(classAIndex < classBIndex) matchingDao!!.getFromLeft(a, type) else matchingDao!!.getFromRight(a, type)
        return ids
    }

    suspend inline fun <reified A: DatabaseItem, reified B: DatabaseItem> match(idA: Long, idB: Long) {
        val classAIndex = daos.keys.indexOf(A::class)
        val classBIndex = daos.keys.indexOf(B::class)
        val type = min(classAIndex, classBIndex) + 100 * max(classAIndex, classBIndex)
        val match = if(classAIndex < classBIndex) ManyManyMatching(idA, idB, type) else ManyManyMatching(idB, idA, type)
        matchingDao!!.upsert(match)
    }

    suspend inline fun <reified A: DatabaseItem, reified B: DatabaseItem> unmatch(idA: Long, idB: Long) {
        val classAIndex = daos.keys.indexOf(A::class)
        val classBIndex = daos.keys.indexOf(B::class)
        val type = min(classAIndex, classBIndex) + 100 * max(classAIndex, classBIndex)
        if(classAIndex < classBIndex) matchingDao!!.deleteMatch(idA, idB, type) else matchingDao!!.deleteMatch(idB, idA, type)
    }

    val matchesStateFlow = matchingDao?.flow()?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @Composable
    inline fun <reified A: DatabaseItem, reified B: DatabaseItem> getMatchesState(a: Long): State<List<Long>> {
        val classAIndex = daos.keys.indexOf(A::class)
        val classBIndex = daos.keys.indexOf(B::class)
        val type = min(classAIndex, classBIndex) + 100 * max(classAIndex, classBIndex)

        val matches by matchesStateFlow!!.collectAsState()
        return remember(a, matches) { derivedStateOf {
            if(classAIndex < classBIndex) matches.filter { it.leftID == a && it.type == type }.map { it.rightID } 
            else matches.filter { it.rightID == a && it.type == type }.map { it.leftID }
        } }
    }


    inline fun <reified E : DatabaseItem> getDao(): TrueDao<E> {
        val dao = daos[E::class] ?: throw Exception("No DAO registered for ${E::class.simpleName}")
        @Suppress("UNCHECKED_CAST")
        return dao as TrueDao<E>
    }

    @Composable
    inline fun <reified E: DatabaseItem> getState(id: Long, crossinline default: () -> E? = {null}): State<E> {
        val data by data<E>().collectAsState()
        val derived = remember { derivedStateOf { (data.firstOrNull { it.id == id } ?: default())!! } }
        return derived
    }

    @Composable
    inline fun <reified E: DatabaseItem> getNullable(id: Long): State<E?> {
        val data by data<E>().collectAsState()
        val derived = remember { derivedStateOf { (data.firstOrNull { it.id == id }) } }
        return derived
    }

    @Composable
    inline fun <reified E : DatabaseItem> getEditable(
        initialId: Long,
        crossinline default: () -> E? = { null }
    ): MutableState<E> {
        // 1. Track the current ID. If it's 0, it will be updated after the first upsert.
        var currentId by remember { mutableLongStateOf(initialId) }

        // 2. Observe the database state
        val data by data<E>().collectAsState(listOf())

        // 3. Local state for immediate UI feedback
        val localState = remember { mutableStateOf<E?>(null) }

        // Sync local state when the database data changes or the ID changes
        LaunchedEffect(data, currentId) {
            val dbItem = data.firstOrNull { it.id == currentId }
            if (dbItem != null) {
                localState.value = dbItem
            }
        }

        // 4. Wrap in a custom MutableState
        return remember {
            object : MutableState<E> {
                override var value: E
                    get() = localState.value ?: default() ?: throw Exception("Entity not found and no default provided")
                    set(newValue) {
                        // Optimistically update the UI local state
                        localState.value = newValue

                        // Push to database
                        upsertAsync(newValue) { newId ->
                            // If this was a new item (ID 0), update our pointer to the new ID
                            if (currentId == 0L) {
                                currentId = newId
                            }
                        }
                    }

                override fun component1(): E = value
                override fun component2(): (E) -> Unit = { value = it }
            }
        }
    }

    val dataStateCache = mutableMapOf<Pair<KClass<*>, String?>, StateFlow<List<*>>>()

    @Suppress("UNCHECKED_CAST")
    inline fun <reified E : DatabaseItem> data(filterQuery: String? = null): StateFlow<List<E>> {
        return dataStateCache.getOrPut(Pair(E::class, filterQuery)) {
            val tableName = E::class.simpleName!!

            callbackFlow<List<E>> {
                // Fetch initial data
                send(getAll<E>(filterQuery))

                // 1. Create an observer for the specific table name
                val observer = object : InvalidationTracker.Observer(tableName) {
                    override fun onInvalidated(tables: Set<String>) {
                        // When the table changes, re-fetch the data
                        launch { send(getAll<E>(filterQuery)) }
                    }
                }

                database.invalidationTracker.addObserver(observer)

                // 3. Clean up the observer when the UI stops listening
                awaitClose { database.invalidationTracker.removeObserver(observer) }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
        } as StateFlow<List<E>>
    }

    suspend inline fun <reified E: DatabaseItem> upsertAll(items: List<E>) {
        getDao<E>().upsertAll(items)
    }

    inline fun <reified E: DatabaseItem> upsertAllAsync(items: List<E>) {
        viewModelScope.launch {
            upsertAll(items)
        }
    }

    suspend inline fun <reified E: DatabaseItem> replaceAll(items: List<E>) {
        database.openHelper.writableDatabase.delete(E::class.simpleName!!, null, null)
        upsertAll(items)
    }

    inline fun <reified E: DatabaseItem> replaceAllAsync(items: List<E>) {
        viewModelScope.launch {
            replaceAll(items)
        }
    }

    suspend inline fun <reified E: DatabaseItem> getAll(filterQuery: String? = null): List<E> {
        return getDao<E>().getAll<E>(filterQuery)
    }

    suspend inline fun <reified E: DatabaseItem> get(id: Long): E {
        return getDao<E>().get<E>(id)
    }

    inline fun <reified E: DatabaseItem> upsertAsync(t: E, noinline andThen: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = getDao<E>().upsert(t)
            andThen(id)
        }
    }

    suspend inline fun <reified E: DatabaseItem> upsert(t: E): Long {
        return getDao<E>().upsert(t)
    }

    inline fun <reified E: DatabaseItem> delete(t: E) {
        viewModelScope.launch {
            getDao<E>().delete(t)
        }
    }

    inline fun <reified E: DatabaseItem> deleteIf(filter: String) {
        viewModelScope.launch {
            getDao<E>().observeNothing(SimpleSQLiteQuery("DELETE FROM ${E::class.simpleName} WHERE $filter"))
        }
    }

    inline fun <reified E: DatabaseItem> update(id: Long, crossinline function: (E) -> E) {
        viewModelScope.launch {
            val t = getDao<E>().get<E>(id)
            getDao<E>().upsert(function(t))
        }
    }
}

interface DatabaseItem {
    val id: Long
}

@Entity
data class ManyManyMatching(
    val leftID: Long,
    val rightID: Long,
    val type: Int,
    @PrimaryKey(autoGenerate = true) val id: Long = 0
)

fun DatabaseItem.isNew() = id == 0L

interface ReorderableDatabaseItem<T: ReorderableDatabaseItem<T>>: DatabaseItem {
    val position: Double
    fun withPosition(position: Double): T
}

@Dao
interface MatchingDao {
    @Upsert
    suspend fun upsert(value: ManyManyMatching): Long
    @Upsert
    suspend fun upsert(value: List<ManyManyMatching>)
    @Delete
    suspend fun delete(value: ManyManyMatching): Int

    @Query("SELECT rightID FROM ManyManyMatching WHERE leftID = :leftID AND type = :type")
    suspend fun getFromLeft(leftID: Long, type: Int): List<Long>
    @Query("SELECT leftID FROM ManyManyMatching WHERE rightID = :rightID AND type = :type")
    suspend fun getFromRight(rightID: Long, type: Int): List<Long>
    @Query("DELETE FROM ManyManyMatching WHERE leftID = :left AND rightID = :right AND type = :type")
    suspend fun deleteMatch(left: Long, right: Long, type: Int)
    @Query("DELETE FROM ManyManyMatching WHERE type = :type")
    suspend fun deleteByType(type: Int)

    @Query("DELETE FROM ManyManyMatching")
    suspend fun clear()
    @Query("SELECT * FROM ManyManyMatching")
    fun flow(): Flow<List<ManyManyMatching>>
}

interface TrueDao<T: DatabaseItem> {
    @Upsert
    suspend fun upsert(value: T): Long
    @Delete
    suspend fun delete(value: T): Int
    @Upsert
    suspend fun upsertAll(t: List<T>)
    @RawQuery
    suspend fun observeRawList(query: SupportSQLiteQuery): List<T>
    @RawQuery
    suspend fun observeRaw(query: SupportSQLiteQuery): T
    @RawQuery
    suspend fun observeNothing(query: SupportSQLiteQuery): Long
    @RawQuery
    suspend fun observeRawNullable(query: SupportSQLiteQuery): T?
}

suspend inline fun <reified E : DatabaseItem> TrueDao<E>.getAll(filterQuery: String? = null): List<E> {
    val tableName = E::class.simpleName!!
    if(filterQuery == null) {
        return observeRawList(SimpleSQLiteQuery("SELECT * FROM $tableName"))
    }
    return observeRawList(SimpleSQLiteQuery("SELECT * FROM $tableName WHERE $filterQuery"))
}

suspend inline fun <reified E : DatabaseItem> TrueDao<E>.get(id: Long): E {
    val tableName = E::class.simpleName!!
    return observeRaw(SimpleSQLiteQuery("SELECT * FROM $tableName WHERE id = $id"))
}

suspend inline fun <reified E : DatabaseItem> TrueDao<E>.getNullable(id: Long): E? {
    val tableName = E::class.simpleName!!
    return observeRawNullable(SimpleSQLiteQuery("SELECT * FROM $tableName WHERE id = $id"))
}

val databases: MutableMap<KClass<*>, RoomDatabase> = mutableMapOf()

private var sqlCipherLoaded = false
fun loadSqlCipher() {
    if (sqlCipherLoaded) return
    try {
        System.loadLibrary("sqlcipher")
        sqlCipherLoaded = true
    } catch (e: UnsatisfiedLinkError) {
        e.printStackTrace()
    }
}

inline fun <reified T : RoomDatabase> Context.buildDatabase(
    migrations: List<Migration> = emptyList(),
    encryptionPassword: String? = null,
    dbName: String = "passwords-db"
): T {
    loadSqlCipher()
    synchronized(databases) {
        if (databases[T::class] != null) return databases[T::class]!! as T

        var password = encryptionPassword
        if (password == null) {
            val helper = BiometricDatabaseHelper(this)
            if (!helper.isKeyGenerated(false)) {
                helper.generateKey(false)
                val cipher = helper.getCipherForEncryption(false)
                password = helper.createAndStorePassphrase(cipher, false)
            } else {
                val cipher = helper.getCipherForDecryption(false)
                password = helper.decryptPassphrase(cipher, false)
            }
        }

        encryptExistingDatabase(this, dbName, password)

        val builder = Room.databaseBuilder(
            this,
            T::class.java,
            dbName
        ).addMigrations(*migrations.toTypedArray())
         .fallbackToDestructiveMigration()

        builder.openHelperFactory(SupportOpenHelperFactory(password.toByteArray(StandardCharsets.UTF_8)))

        val db = builder.build()
        databases[T::class] = db
        return db as T
    }
}

fun encryptExistingDatabase(context: Context, dbName: String, password: String) {
    loadSqlCipher()
    val dbFile = context.getDatabasePath(dbName)
    if (!dbFile.exists() || dbFile.length() < 16) return

    val isEncrypted = try {
        FileInputStream(dbFile).use { fis ->
            val header = ByteArray(16)
            if (fis.read(header) != 16) {
                true
            } else {
                !header.contentEquals("SQLite format 3\u0000".toByteArray(StandardCharsets.UTF_8))
            }
        }
    } catch (e: Exception) {
        true
    }

    if (isEncrypted) return

    // It's not encrypted. Let's encrypt it.
    val tempFile = context.getDatabasePath("${dbName}_temp")
    if (tempFile.exists()) tempFile.delete()
    tempFile.parentFile?.mkdirs()
    tempFile.createNewFile()

    try {
        val db = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            "",
            null,
            net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE,
            null
        )
        db.rawExecSQL("PRAGMA cipher_compatibility = 4")
        db.rawExecSQL("ATTACH DATABASE '${tempFile.absolutePath}' AS encrypted KEY '${password}'")
        db.rawExecSQL("SELECT sqlcipher_export('encrypted')")
        db.rawExecSQL("DETACH DATABASE encrypted")
        db.close()

        // Delete the original plain database and its journal/WAL files
        dbFile.delete()
        File("${dbFile.path}-wal").delete()
        File("${dbFile.path}-shm").delete()
        File("${dbFile.path}-journal").delete()

        tempFile.renameTo(dbFile)
    } catch (e: net.zetetic.database.sqlcipher.SQLiteNotADatabaseException) {
        tempFile.delete()
    }
}

class DefaultConverters {
    @TypeConverter
    fun fromInstant(value: Instant) = value.epochSeconds
    @TypeConverter
    fun toInstant(value: Long) = Instant.fromEpochSeconds(value)
    @TypeConverter
    fun fromList(value: List<Long>?): String? {
        return value?.let { Json.encodeToString(it) }
    }
    @TypeConverter
    fun toList(value: String?): List<Long>? {
        return value?.let { Json.decodeFromString<List<Long>>(it) }
    }
    @TypeConverter
    fun fromListS(value: List<String>): String {
        return Json.encodeToString(value)
    }
    @TypeConverter
    fun toListS(value: String): List<String> {
        return Json.decodeFromString<List<String>>(value)
    }

    @TypeConverter
    fun fromDuration(value: Duration) = value.inWholeMilliseconds
    @TypeConverter
    fun toDuration(value: Long) = value.milliseconds

    @TypeConverter
    fun fromLocalTime(value: LocalTime) = value.toSecondOfDay()
    @TypeConverter
    fun toLocalTime(value: Int) = LocalTime.fromSecondOfDay(value)
}

class BiometricDatabaseHelper(val context: Context) {
    private fun keyStoreAlias(useBiometrics: Boolean) = if (useBiometrics) "db_auth_key" else "db_no_auth_key"
    private val sharedPrefsName = "secure_prefs"
    private fun passphraseKey(useBiometrics: Boolean) = if (useBiometrics) "encrypted_passphrase" else "encrypted_passphrase_no_auth"
    private fun ivKey(useBiometrics: Boolean) = if (useBiometrics) "passphrase_iv" else "passphrase_iv_no_auth"

    fun isKeyGenerated(useBiometrics: Boolean): Boolean {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        return keyStore.containsAlias(keyStoreAlias(useBiometrics))
    }

    fun generateKey(useBiometrics: Boolean) {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(
            keyStoreAlias(useBiometrics),
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(useBiometrics)

        if (useBiometrics) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            builder.setInvalidatedByBiometricEnrollment(false)
        }

        keyGenerator.init(builder.build())
        keyGenerator.generateKey()
    }

    private fun getSecretKey(useBiometrics: Boolean): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        return keyStore.getKey(keyStoreAlias(useBiometrics), null) as SecretKey
    }

    fun createAndStorePassphrase(cipher: Cipher, useBiometrics: Boolean): String {
        val random = SecureRandom()
        val passphraseBytes = ByteArray(32)
        random.nextBytes(passphraseBytes)
        val passphrase = Base64.encodeToString(passphraseBytes, Base64.NO_WRAP)

        val encryptedBytes = cipher.doFinal(passphrase.toByteArray(StandardCharsets.UTF_8))
        val iv = cipher.iv

        val prefs = context.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE)
        prefs.edit {
            putString(passphraseKey(useBiometrics), Base64.encodeToString(encryptedBytes, Base64.NO_WRAP))
                .putString(ivKey(useBiometrics), Base64.encodeToString(iv, Base64.NO_WRAP))
        }

        return passphrase
    }

    fun decryptPassphrase(cipher: Cipher, useBiometrics: Boolean): String {
        val prefs = context.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE)
        val encryptedPassphrase = prefs.getString(passphraseKey(useBiometrics), null) ?: throw Exception("Passphrase not found")
        val encryptedBytes = Base64.decode(encryptedPassphrase, Base64.NO_WRAP)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes, StandardCharsets.UTF_8)
    }

    fun getCipherForEncryption(useBiometrics: Boolean): Cipher {
        val cipher = Cipher.getInstance("${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/${KeyProperties.ENCRYPTION_PADDING_NONE}")
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey(useBiometrics))
        return cipher
    }

    fun getCipherForDecryption(useBiometrics: Boolean): Cipher {
        val prefs = context.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE)
        val ivBase64 = prefs.getString(ivKey(useBiometrics), null) ?: throw Exception("IV not found")
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/${KeyProperties.ENCRYPTION_PADDING_NONE}")
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(useBiometrics), GCMParameterSpec(128, iv))
        return cipher
    }

    fun getPassphrase(useBiometrics: Boolean): String {
        return try {
            decryptPassphrase(getCipherForDecryption(useBiometrics), useBiometrics)
        } catch (e: Exception) {
            ""
        }
    }
}

fun unlockDatabaseWithBiometrics(
    activity: FragmentActivity,
    onSuccess: (String) -> Unit,
    onFailure: () -> Unit
) {
    val helper = BiometricDatabaseHelper(activity)
    val executor = ContextCompat.getMainExecutor(activity)

    if (!helper.isKeyGenerated(true)) {
        helper.generateKey(true)
        val biometricPrompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val cipher = result.cryptoObject?.cipher!!
                val passphrase = helper.createAndStorePassphrase(cipher, true)
                onSuccess(passphrase)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFailure()
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Setup Secure Database")
            .setSubtitle("Authenticate to create your secure encryption key")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancel")
            .build()

        biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(helper.getCipherForEncryption(true)))
    } else {
        val biometricPrompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val cipher = result.cryptoObject?.cipher!!
                val passphrase = helper.decryptPassphrase(cipher, true)
                onSuccess(passphrase)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFailure()
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Database")
            .setSubtitle("Authenticate to access your secure data")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancel")
            .build()

        biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(helper.getCipherForDecryption(true)))
    }
}
