package com.zeropointsix.dobaosay.credential

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

@JvmInline
value class CredentialKey private constructor(val value: String) {
    companion object {
        private val validKey = Regex("[a-z][a-z0-9_.-]{0,63}")

        fun of(value: String): CredentialKey {
            require(validKey.matches(value)) { "Credential key is invalid" }
            return CredentialKey(value)
        }
    }
}

/** Owns a defensive copy of secret bytes. Callers must close it as soon as practical. */
class SecretBytes private constructor(bytes: ByteArray) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val owned = bytes.copyOf()

    val size: Int
        get() = synchronized(owned) { ensureOpen().size }

    /** Supplies a temporary copy and wipes it when [block] completes. */
    suspend fun <T> useBytes(block: suspend (ByteArray) -> T): T {
        val temporary = synchronized(owned) { ensureOpen().copyOf() }
        return try {
            block(temporary)
        } finally {
            temporary.fill(0)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            synchronized(owned) { owned.fill(0) }
        }
    }

    override fun toString(): String = "SecretBytes(redacted)"

    private fun ensureOpen(): ByteArray {
        check(!closed.get()) { "SecretBytes is closed" }
        return owned
    }

    companion object {
        fun copyOf(bytes: ByteArray): SecretBytes {
            require(bytes.isNotEmpty()) { "Secret must not be empty" }
            return SecretBytes(bytes)
        }
    }
}

/** A closeable plaintext lease returned by a successful vault read. */
class SecretLease internal constructor(
    bytes: ByteArray,
    val metadata: CredentialMetadata,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val owned = bytes.copyOf()

    /**
     * Supplies a temporary defensive copy and wipes that copy after [block] returns. Retaining the
     * supplied array is unsupported; it will contain zeroes after this call.
     */
    fun <T> useBytes(block: (ByteArray) -> T): T {
        val temporary = synchronized(owned) { ensureOpen().copyOf() }
        return try {
            block(temporary)
        } finally {
            temporary.fill(0)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            synchronized(owned) { owned.fill(0) }
        }
    }

    override fun toString(): String = "SecretLease(metadata=$metadata, secret=redacted)"

    private fun ensureOpen(): ByteArray {
        check(!closed.get()) { "SecretLease is closed" }
        return owned
    }
}

data class CredentialMetadata(
    val revision: Long,
    val expiresAt: Instant?,
) {
    init {
        require(revision > 0) { "Revision must be positive" }
    }
}

/** Owns protected bytes crossing the protector/store boundary. */
class ProtectedPayload private constructor(bytes: ByteArray) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val owned = bytes.copyOf()

    /** Supplies a temporary copy and wipes it when [block] completes. */
    suspend fun <T> useBytes(block: suspend (ByteArray) -> T): T {
        val temporary = synchronized(owned) { ensureOpen().copyOf() }
        return try {
            block(temporary)
        } finally {
            temporary.fill(0)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            synchronized(owned) { owned.fill(0) }
        }
    }

    override fun toString(): String = "ProtectedPayload(redacted)"

    private fun ensureOpen(): ByteArray {
        check(!closed.get()) { "ProtectedPayload is closed" }
        return owned
    }

    companion object {
        fun copyOf(bytes: ByteArray): ProtectedPayload {
            require(bytes.isNotEmpty()) { "Protected payload must not be empty" }
            return ProtectedPayload(bytes)
        }
    }
}

class StoredCredential(
    val metadata: CredentialMetadata,
    val payload: ProtectedPayload,
) : AutoCloseable {
    override fun close() = payload.close()

    override fun toString(): String = "StoredCredential(metadata=$metadata, payload=redacted)"
}
