package ca.gapwise.android.data.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ca.gapwise.android.core.model.Meeting
import ca.gapwise.android.core.persistence.MeetingJson
import ca.gapwise.android.data.account.AccountSession
import ca.gapwise.android.data.account.GapwiseAccountManager
import ca.gapwise.android.data.account.GapwiseCloudConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Native implementation of Gapwise's existing encrypted private-cloud protocol.
 * Raw .ics bytes never leave the device; only normalized state is encrypted and synced.
 */
class EncryptedCloudSync(private val accounts: GapwiseAccountManager) {
    data class Result(val meetings: List<Meeting>, val message: String)

    suspend fun pullOrInitialize(localMeetings: List<Meeting>): Result = withContext(Dispatchers.IO) {
        val session = accounts.requireSession()
        val keys = requestKeys(session)
        try {
            val privateRow = loadRow(PRIVATE_TABLE, session, "record_id", 262_160)
            val capsuleRow = loadRow(AVAILABILITY_TABLE, session, "capsule_id", 8_208)
            require((privateRow == null) == (capsuleRow == null)) {
                "Encrypted cloud data is incomplete. Sync again from the originating device."
            }
            if (privateRow == null) {
                if (localMeetings.isNotEmpty()) writeState(session, keys, localMeetings, null, null)
                return@withContext Result(
                    localMeetings,
                    if (localMeetings.isEmpty()) "Encrypted sync enabled. Nothing is stored in the cloud yet."
                    else "Encrypted timetable sync enabled.",
                )
            }
            validateContext(privateRow, keys, privateData = true)
            validateContext(capsuleRow!!, keys, privateData = false)
            val payload = decryptJson(privateRow, keys.privateDek, "private-data")
            require(payload.optInt("schemaVersion") == PRIVATE_SCHEMA_VERSION) {
                "Cloud timetable uses an unsupported private-data version."
            }
            val meetings = MeetingJson.fromWebArray(payload.optJSONArray("schedule") ?: JSONArray())
            Result(meetings, "Synced ${meetings.size} class meetings from your Gapwise account.")
        } finally {
            keys.clear()
        }
    }

    suspend fun pushSchedule(meetings: List<Meeting>): Result = withContext(Dispatchers.IO) {
        val session = accounts.requireSession()
        val keys = requestKeys(session)
        try {
            val privateRow = loadRow(PRIVATE_TABLE, session, "record_id", 262_160)
            val capsuleRow = loadRow(AVAILABILITY_TABLE, session, "capsule_id", 8_208)
            require((privateRow == null) == (capsuleRow == null)) {
                "Encrypted cloud data is incomplete. Reload before syncing."
            }
            privateRow?.let { validateContext(it, keys, privateData = true) }
            capsuleRow?.let { validateContext(it, keys, privateData = false) }
            writeState(session, keys, meetings, privateRow, capsuleRow)
            Result(meetings, "Synced ${meetings.size} class meetings to your Gapwise account.")
        } finally {
            keys.clear()
        }
    }

    private fun writeState(
        session: AccountSession,
        keys: DataKeys,
        meetings: List<Meeting>,
        currentPrivate: CloudRow?,
        currentCapsule: CloudRow?,
    ) {
        val payload = currentPrivate?.let { decryptJson(it, keys.privateDek, "private-data") }
            ?: defaultPrivatePayload()
        payload.put("schemaVersion", PRIVATE_SCHEMA_VERSION)
        payload.put("schedule", MeetingJson.toWebArray(meetings))

        val privateRevision = (currentPrivate?.revision ?: 0) + 1
        val privateId = currentPrivate?.recordId ?: UUID.randomUUID().toString()
        val privateEncrypted = encryptJson(
            payload,
            keys.privateDek,
            aad("private-data", PRIVATE_SCHEMA_VERSION, keys.subjectId, privateId, keys.privateKeyId, privateRevision),
        )
        val privateJson = JSONObject()
            .put("user_id", session.userId)
            .put("subject_id", keys.subjectId)
            .put("record_id", privateId)
            .put("key_id", keys.privateKeyId)
            .put("ciphertext", byteaHex(privateEncrypted.ciphertext))
            .put("nonce", byteaHex(privateEncrypted.nonce))
            .put("crypto_version", CRYPTO_VERSION)
            .put("schema_version", PRIVATE_SCHEMA_VERSION)
            .put("revision", privateRevision)

        val capsuleRevision = (currentCapsule?.revision ?: 0) + 1
        val capsuleId = currentCapsule?.recordId ?: UUID.randomUUID().toString()
        val capsuleEncrypted = encryptJson(
            deriveAvailabilityCapsule(payload),
            keys.availabilityDek,
            aad(
                "friend-availability",
                AVAILABILITY_SCHEMA_VERSION,
                keys.subjectId,
                capsuleId,
                keys.availabilityKeyId,
                capsuleRevision,
            ),
        )
        val capsuleJson = JSONObject()
            .put("user_id", session.userId)
            .put("subject_id", keys.subjectId)
            .put("capsule_id", capsuleId)
            .put("key_id", keys.availabilityKeyId)
            .put("ciphertext", byteaHex(capsuleEncrypted.ciphertext))
            .put("nonce", byteaHex(capsuleEncrypted.nonce))
            .put("crypto_version", CRYPTO_VERSION)
            .put("schema_version", AVAILABILITY_SCHEMA_VERSION)
            .put("revision", capsuleRevision)

        writeRow(PRIVATE_TABLE, session, privateJson, currentPrivate)
        writeRow(AVAILABILITY_TABLE, session, capsuleJson, currentCapsule)
    }

    private data class CloudRow(
        val userId: String,
        val subjectId: String,
        val recordId: String,
        val keyId: String,
        val ciphertext: ByteArray,
        val nonce: ByteArray,
        val cryptoVersion: Int,
        val schemaVersion: Int,
        val revision: Int,
    )

    private fun loadRow(
        table: String,
        session: AccountSession,
        idColumn: String,
        maximumCiphertextBytes: Int,
    ): CloudRow? {
        val response = request(
            url = "${GapwiseCloudConfig.SUPABASE_URL}/rest/v1/$table?user_id=eq.${session.userId}&select=*",
            method = "GET",
            accessToken = session.accessToken,
            includeApiKey = true,
        )
        require(response.code in 200..299) { "Encrypted cloud read failed." }
        val rows = JSONArray(response.body)
        require(rows.length() <= 1) { "Encrypted cloud contains duplicate private records." }
        if (rows.length() == 0) return null
        return rows.getJSONObject(0).let { row ->
            CloudRow(
                userId = row.getString("user_id"),
                subjectId = row.getString("subject_id"),
                recordId = row.getString(idColumn),
                keyId = row.getString("key_id"),
                ciphertext = byteaBytes(row.getString("ciphertext"), maximumCiphertextBytes),
                nonce = byteaBytes(row.getString("nonce"), 12),
                cryptoVersion = row.getInt("crypto_version"),
                schemaVersion = row.getInt("schema_version"),
                revision = row.getInt("revision"),
            )
        }
    }

    private fun writeRow(table: String, session: AccountSession, row: JSONObject, current: CloudRow?) {
        val response = if (current == null) {
            request(
                url = "${GapwiseCloudConfig.SUPABASE_URL}/rest/v1/$table",
                method = "POST",
                body = row.toString(),
                accessToken = session.accessToken,
                includeApiKey = true,
                preferRepresentation = true,
            )
        } else {
            val update = JSONObject(row.toString()).apply { remove("user_id") }
            request(
                url = "${GapwiseCloudConfig.SUPABASE_URL}/rest/v1/$table?user_id=eq.${session.userId}&revision=eq.${current.revision}",
                method = "PATCH",
                body = update.toString(),
                accessToken = session.accessToken,
                includeApiKey = true,
                preferRepresentation = true,
            )
        }
        require(response.code in 200..299) { "Encrypted cloud upload failed." }
        val written = JSONArray(response.body)
        require(written.length() == 1 && written.getJSONObject(0).optInt("revision") == row.getInt("revision")) {
            "Encrypted cloud data changed on another device. Reload before syncing."
        }
    }

    private data class DataKeys(
        val subjectId: String,
        val privateKeyId: String,
        val privateDek: ByteArray,
        val availabilityKeyId: String,
        val availabilityDek: ByteArray,
    ) {
        fun clear() {
            privateDek.fill(0)
            availabilityDek.fill(0)
        }
    }

    private fun requestKeys(session: AccountSession): DataKeys {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(DEVICE_KEY_ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(DEVICE_KEY_ALIAS, KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(2048)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                        .build(),
                )
            }.generateKeyPair()
        }
        val privateKey = keyStore.getKey(DEVICE_KEY_ALIAS, null)
        val publicKey = keyStore.getCertificate(DEVICE_KEY_ALIAS).publicKey as RSAPublicKey
        val jwk = JSONObject()
            .put("kty", "RSA")
            .put("alg", "RSA-OAEP-256")
            .put("e", base64Url(unsignedFixed(publicKey.publicExponent, 3)))
            .put("n", base64Url(unsignedFixed(publicKey.modulus, 256)))
            .put("ext", true)
            .put("key_ops", JSONArray().put("encrypt"))

        val response = request(
            url = GapwiseCloudConfig.KEY_BROKER_URL,
            method = "POST",
            body = JSONObject().put("devicePublicKey", jwk).toString(),
            accessToken = session.accessToken,
            includeApiKey = false,
        )
        require(response.code in 200..299) { "Encrypted key setup is temporarily unavailable." }
        require(response.body.toByteArray(Charsets.UTF_8).size <= 8 * 1024) { "Key broker response is too large." }
        val body = JSONObject(response.body)
        require(body.getInt("cryptoVersion") == CRYPTO_VERSION && body.getInt("keyVersion") == KEY_VERSION) {
            "Key broker returned an unsupported crypto version."
        }
        val privateData = body.getJSONObject("privateData")
        val availability = body.getJSONObject("friendAvailability")
        return DataKeys(
            subjectId = body.getString("subjectId"),
            privateKeyId = privateData.getString("keyId"),
            privateDek = unwrapDek(privateData.getString("wrappedDek"), privateKey),
            availabilityKeyId = availability.getString("keyId"),
            availabilityDek = unwrapDek(availability.getString("wrappedDek"), privateKey),
        )
    }

    private fun validateContext(row: CloudRow, keys: DataKeys, privateData: Boolean) {
        require(row.userId.isNotBlank() && row.subjectId == keys.subjectId) { "Encrypted cloud context mismatch." }
        require(row.cryptoVersion == CRYPTO_VERSION && row.revision >= 1) { "Encrypted cloud context mismatch." }
        if (privateData) {
            require(row.keyId == keys.privateKeyId && row.schemaVersion == PRIVATE_SCHEMA_VERSION) {
                "Encrypted private-data context mismatch."
            }
        } else {
            require(row.keyId == keys.availabilityKeyId && row.schemaVersion == AVAILABILITY_SCHEMA_VERSION) {
                "Encrypted availability context mismatch."
            }
        }
    }

    private fun unwrapDek(encoded: String, privateKey: java.security.Key): ByteArray {
        val wrapped = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        require(wrapped.size == 256) { "Device-wrapped key has an invalid length." }
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            privateKey,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
        )
        return cipher.doFinal(wrapped).also { require(it.size == 32) { "Device key unwrap failed." } }
    }

    private data class Encrypted(val ciphertext: ByteArray, val nonce: ByteArray)

    private fun encryptJson(value: JSONObject, key: ByteArray, aad: ByteArray): Encrypted {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return Encrypted(cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8)), nonce)
    }

    private fun decryptJson(row: CloudRow, key: ByteArray, purpose: String): JSONObject {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, row.nonce))
        cipher.updateAAD(aad(purpose, row.schemaVersion, row.subjectId, row.recordId, row.keyId, row.revision))
        return JSONObject(cipher.doFinal(row.ciphertext).toString(Charsets.UTF_8))
    }

    private fun aad(
        purpose: String,
        schemaVersion: Int,
        subjectId: String,
        recordId: String,
        keyId: String,
        revision: Int,
    ): ByteArray = JSONArray()
        .put("gapwise")
        .put(purpose)
        .put(CRYPTO_VERSION)
        .put(schemaVersion)
        .put(subjectId)
        .put(recordId)
        .put(keyId)
        .put(revision)
        .toString()
        .toByteArray(Charsets.UTF_8)

    private fun defaultPrivatePayload() = JSONObject()
        .put("schemaVersion", PRIVATE_SCHEMA_VERSION)
        .put("schedule", JSONArray())
        .put("personalItems", JSONArray())
        .put(
            "preferences",
            JSONObject()
                .put("mode", "fastest")
                .put("walkingSpeedMps", 1.35)
                .put("transitionBufferMinutes", 5)
                .put("avoidStairs", false)
                .put("preferIndoor", false)
                .put("dayOrigin", "commute")
                .put("residenceBuildingCode", JSONObject.NULL)
                .put("commuteMode", JSONObject.NULL)
                .put("campusAccessPointId", JSONObject.NULL),
        )
        .put(
            "gapPreferences",
            JSONObject()
                .put("setupMinutes", 4)
                .put("packUpMinutes", 3)
                .put("lunchWindowStart", 690)
                .put("lunchWindowEnd", 870)
                .put("mealDurationMinutes", 30)
                .put("willingToLeaveCampus", false)
                .put("oneWayHomeCommuteMinutes", JSONObject.NULL)
                .put("minimumHomeStayMinutes", 90)
                .put("homeTurnaroundMinutes", 10)
                .put("riskTolerance", "low"),
        )
        .put(
            "academic",
            JSONObject()
                .put("coursework", JSONArray())
                .put("blocks", JSONArray())
                .put("proposalRevision", JSONObject.NULL),
        )

    private data class BusyEvent(val term: String, val weekday: String, val start: Int, val end: Int)
    private data class Window(val weekday: String, val start: Int, val end: Int)

    private fun deriveAvailabilityCapsule(payload: JSONObject): JSONObject {
        val busy = mutableListOf<BusyEvent>()
        val schedule = payload.optJSONArray("schedule") ?: JSONArray()
        for (index in 0 until schedule.length()) {
            val item = schedule.optJSONObject(index) ?: continue
            busy += BusyEvent(item.optString("term"), item.optString("weekday"), item.optInt("startTime"), item.optInt("endTime"))
        }
        val personal = payload.optJSONArray("personalItems") ?: JSONArray()
        for (index in 0 until personal.length()) {
            val item = personal.optJSONObject(index) ?: continue
            if (item.optJSONObject("flexibility")?.optString("kind") != "fixed") continue
            if (!item.has("startTime") || !item.has("endTime")) continue
            busy += BusyEvent(item.optString("term"), item.optString("weekday"), item.optInt("startTime"), item.optInt("endTime"))
        }

        val terms = JSONObject()
        TERMS.forEach { term ->
            val windows = WEEKDAYS.flatMap { weekday ->
                windowsForDay(busy.filter { it.term == term }, weekday)
            }.sortedWith(windowRank).take(8).sortedWith(windowCanonical)
            terms.put(
                term,
                JSONArray().apply {
                    windows.forEach { put(JSONObject().put("weekday", it.weekday).put("startMinute", it.start).put("endMinute", it.end)) }
                },
            )
        }
        return JSONObject().put("schemaVersion", AVAILABILITY_SCHEMA_VERSION).put("terms", terms)
    }

    private fun windowsForDay(events: List<BusyEvent>, weekday: String): List<Window> {
        val sorted = events
            .filter { it.weekday == weekday && it.start in 0..1439 && it.end in 1..1440 && it.end > it.start }
            .sortedWith(compareBy<BusyEvent>({ it.start }, { it.end }))
        val merged = mutableListOf<Pair<Int, Int>>()
        sorted.forEach { event ->
            val last = merged.lastOrNull()
            if (last == null || event.start > last.second) merged += event.start to event.end
            else merged[merged.lastIndex] = last.first to maxOf(last.second, event.end)
        }
        val candidates = mutableListOf<Window>()
        for (index in 1 until merged.size) {
            val previous = merged[index - 1]
            val next = merged[index]
            val start = ceilTo(maxOf(previous.second + 15, 9 * 60), 30)
            val end = floorTo(minOf(next.first - 15, 18 * 60), 30)
            if (end - start >= 60) candidates += Window(weekday, start, end)
        }
        return candidates.sortedWith(windowRank).take(2)
    }

    private val windowRank = Comparator<Window> { left, right ->
        val duration = (right.end - right.start) - (left.end - left.start)
        if (duration != 0) duration
        else WEEKDAYS.indexOf(left.weekday).compareTo(WEEKDAYS.indexOf(right.weekday)).takeIf { it != 0 }
            ?: left.start.compareTo(right.start).takeIf { it != 0 }
            ?: left.end.compareTo(right.end)
    }

    private val windowCanonical = Comparator<Window> { left, right ->
        WEEKDAYS.indexOf(left.weekday).compareTo(WEEKDAYS.indexOf(right.weekday)).takeIf { it != 0 }
            ?: left.start.compareTo(right.start).takeIf { it != 0 }
            ?: left.end.compareTo(right.end)
    }

    private fun ceilTo(value: Int, unit: Int) = ((value + unit - 1) / unit) * unit
    private fun floorTo(value: Int, unit: Int) = (value / unit) * unit

    private data class HttpResult(val code: Int, val body: String)

    private fun request(
        url: String,
        method: String,
        body: String? = null,
        accessToken: String,
        includeApiKey: Boolean,
        preferRepresentation: Boolean = false,
    ): HttpResult {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            // The first-party broker enforces the same explicit origin contract as the web client.
            if (url == GapwiseCloudConfig.KEY_BROKER_URL) {
                val endpoint = URL(url)
                setRequestProperty("Origin", "${endpoint.protocol}://${endpoint.authority}")
            }
            if (includeApiKey) setRequestProperty("apikey", GapwiseCloudConfig.SUPABASE_PUBLISHABLE_KEY)
            if (preferRepresentation) setRequestProperty("Prefer", "return=representation")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText().take(512 * 1024) }.orEmpty()
        connection.disconnect()
        return HttpResult(code, text)
    }

    private fun byteaHex(bytes: ByteArray): String = "\\x" + bytes.joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private fun byteaBytes(value: String, maximumBytes: Int): ByteArray {
        require(value.startsWith("\\x") && (value.length - 2) % 2 == 0) { "Invalid encrypted bytea value." }
        val hex = value.substring(2)
        require(hex.length / 2 <= maximumBytes) { "Encrypted value is too large." }
        return ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun base64Url(bytes: ByteArray): String = Base64.encodeToString(
        bytes,
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    private fun unsignedFixed(value: BigInteger, size: Int): ByteArray {
        val raw = value.toByteArray().let { bytes ->
            if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        }
        require(raw.size <= size) { "RSA public key is too large." }
        return ByteArray(size).also { raw.copyInto(it, destinationOffset = size - raw.size) }
    }

    private companion object {
        const val PRIVATE_TABLE = "encrypted_private_data"
        const val AVAILABILITY_TABLE = "encrypted_friend_availability"
        const val DEVICE_KEY_ALIAS = "gapwise.android.cloud.device.v1"
        const val CRYPTO_VERSION = 1
        const val KEY_VERSION = 1
        const val PRIVATE_SCHEMA_VERSION = 2
        const val AVAILABILITY_SCHEMA_VERSION = 1
        val TERMS = listOf("Fall", "Winter", "Summer")
        val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    }
}
