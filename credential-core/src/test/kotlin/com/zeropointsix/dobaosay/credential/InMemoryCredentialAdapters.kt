package com.zeropointsix.dobaosay.credential

import kotlinx.coroutines.CompletableDeferred
import java.time.Instant

internal class TestProtector : CredentialProtector {
    var sealUnavailable = false
    var openUnavailable = false
    var corruptOnOpen = false
    var throwOnSeal: Exception? = null
    var throwOnOpen: Exception? = null
    var sealEntered: CompletableDeferred<Unit>? = null
    var sealGate: CompletableDeferred<Unit>? = null

    override suspend fun seal(secret: SecretBytes): SealResult {
        sealEntered?.complete(Unit)
        sealGate?.await()
        throwOnSeal?.let { throw it }
        if (sealUnavailable) return SealResult.Unavailable(CredentialErrorCode.PROTECTOR_UNAVAILABLE)
        return secret.useBytes { plain ->
            SealResult.Sealed(ProtectedPayload.copyOf(transform(plain)))
        }
    }

    override suspend fun open(payload: ProtectedPayload): OpenResult {
        throwOnOpen?.let { throw it }
        if (openUnavailable) return OpenResult.Unavailable(CredentialErrorCode.PROTECTOR_UNAVAILABLE)
        if (corruptOnOpen) return OpenResult.Corrupt
        return payload.useBytes { sealed ->
            OpenResult.Opened(SecretBytes.copyOf(transform(sealed)))
        }
    }

    private fun transform(bytes: ByteArray): ByteArray = ByteArray(bytes.size) { index -> (bytes[index].toInt() xor 0x5A).toByte() }
}

internal class InMemorySealedCredentialStore : SealedCredentialStore {
    private data class Entry(
        val revision: Long,
        val expiresAt: Instant?,
        val payload: ByteArray,
    )

    private val lock = Any()
    private val entries = mutableMapOf<CredentialKey, Entry>()
    private var nextRevision = 1L

    var failWrites = false
    var failReads = false
    var failDeletes = false
    var failClears = false
    var throwOnWrite: Throwable? = null
    var writeEntered: CompletableDeferred<Unit>? = null
    var writeGate: CompletableDeferred<Unit>? = null

    override suspend fun read(key: CredentialKey): StoreReadResult {
        if (failReads) return StoreReadResult.Unavailable(CredentialErrorCode.STORE_UNAVAILABLE)
        val snapshot = synchronized(lock) { entries[key]?.let { it.copy(payload = it.payload.copyOf()) } }
            ?: return StoreReadResult.Missing
        return StoreReadResult.Found(
            StoredCredential(
                CredentialMetadata(snapshot.revision, snapshot.expiresAt),
                ProtectedPayload.copyOf(snapshot.payload),
            ),
        ).also { snapshot.payload.fill(0) }
    }

    override suspend fun compareAndSet(
        key: CredentialKey,
        expectedRevision: Long?,
        expiresAt: Instant?,
        payload: ProtectedPayload,
    ): StoreWriteResult {
        writeEntered?.complete(Unit)
        writeGate?.await()
        throwOnWrite?.let { throw it }
        if (failWrites) return StoreWriteResult.Unavailable(CredentialErrorCode.STORE_UNAVAILABLE)
        return payload.useBytes { temporary ->
            synchronized(lock) {
                val current = entries[key]
                if (current?.revision != expectedRevision) {
                    StoreWriteResult.Conflict(current?.revision)
                } else {
                    val revision = nextRevision++
                    entries.put(key, Entry(revision, expiresAt, temporary.copyOf()))?.payload?.fill(0)
                    StoreWriteResult.Applied(revision)
                }
            }
        }
    }

    override suspend fun delete(key: CredentialKey): StoreDeleteResult {
        if (failDeletes) return StoreDeleteResult.Unavailable(CredentialErrorCode.STORE_UNAVAILABLE)
        val removed = synchronized(lock) { entries.remove(key) }
        removed?.payload?.fill(0)
        return if (removed == null) StoreDeleteResult.Missing else StoreDeleteResult.Deleted
    }

    override suspend fun clear(): StoreClearResult {
        if (failClears) return StoreClearResult.Unavailable(CredentialErrorCode.STORE_UNAVAILABLE)
        synchronized(lock) {
            entries.values.forEach { it.payload.fill(0) }
            entries.clear()
        }
        return StoreClearResult.Cleared
    }

    fun overwriteStoredCopy(key: CredentialKey, replacement: ByteArray) {
        synchronized(lock) {
            val current = entries.getValue(key)
            current.payload.fill(0)
            entries[key] = current.copy(payload = replacement.copyOf())
        }
    }
}
