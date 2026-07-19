package com.zeropointsix.dobaosay.credential

import java.time.Instant

enum class CredentialErrorCode {
    PROTECTOR_UNAVAILABLE,
    STORE_UNAVAILABLE,
    INTERNAL_FAILURE,
}

sealed interface SealResult {
    class Sealed(
        val payload: ProtectedPayload,
    ) : SealResult {
        override fun toString(): String = "SealResult.Sealed(payload=redacted)"
    }

    data class Unavailable(
        val code: CredentialErrorCode,
    ) : SealResult
}

sealed interface OpenResult {
    class Opened(
        val secret: SecretBytes,
    ) : OpenResult {
        override fun toString(): String = "OpenResult.Opened(secret=redacted)"
    }

    data object Corrupt : OpenResult

    data class Unavailable(
        val code: CredentialErrorCode,
    ) : OpenResult
}

interface CredentialProtector {
    suspend fun seal(secret: SecretBytes): SealResult

    suspend fun open(payload: ProtectedPayload): OpenResult
}

sealed interface StoreReadResult {
    data object Missing : StoreReadResult

    class Found(
        val credential: StoredCredential,
    ) : StoreReadResult {
        override fun toString(): String = "StoreReadResult.Found(credential=$credential)"
    }

    data class Unavailable(
        val code: CredentialErrorCode,
    ) : StoreReadResult
}

sealed interface StoreWriteResult {
    data class Applied(
        val revision: Long,
    ) : StoreWriteResult

    data class Conflict(
        val currentRevision: Long?,
    ) : StoreWriteResult

    data class Unavailable(
        val code: CredentialErrorCode,
    ) : StoreWriteResult
}

sealed interface StoreDeleteResult {
    data object Deleted : StoreDeleteResult

    data object Missing : StoreDeleteResult

    data class Unavailable(
        val code: CredentialErrorCode,
    ) : StoreDeleteResult
}

sealed interface StoreClearResult {
    data object Cleared : StoreClearResult

    data class Unavailable(
        val code: CredentialErrorCode,
    ) : StoreClearResult
}

/**
 * Storage port. Implementations must defensively copy payloads and make compare-and-set atomic.
 * Cancellation before a mutation commits must leave the prior value unchanged.
 */
interface SealedCredentialStore {
    suspend fun read(key: CredentialKey): StoreReadResult

    suspend fun compareAndSet(
        key: CredentialKey,
        expectedRevision: Long?,
        expiresAt: Instant?,
        payload: ProtectedPayload,
    ): StoreWriteResult

    suspend fun delete(key: CredentialKey): StoreDeleteResult

    suspend fun clear(): StoreClearResult
}

enum class CredentialDiagnosticCode {
    READ_MISSING,
    READ_EXPIRED,
    READ_CORRUPT,
    READ_AVAILABLE,
    READ_UNAVAILABLE,
    WRITE_APPLIED,
    WRITE_CONFLICT,
    WRITE_UNAVAILABLE,
    DELETE_APPLIED,
    DELETE_MISSING,
    DELETE_UNAVAILABLE,
    CLEAR_APPLIED,
    CLEAR_UNAVAILABLE,
    OPERATION_CANCELLED,
    INTERNAL_FAILURE,
}

fun interface CredentialDiagnostics {
    fun record(code: CredentialDiagnosticCode)

    companion object {
        val NONE = CredentialDiagnostics { }
    }
}
