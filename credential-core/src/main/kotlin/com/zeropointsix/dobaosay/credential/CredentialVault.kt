package com.zeropointsix.dobaosay.credential

import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Instant

sealed interface WriteCondition {
    data object IfAbsent : WriteCondition

    data class IfRevision(
        val revision: Long,
    ) : WriteCondition {
        init {
            require(revision > 0) { "Revision must be positive" }
        }
    }
}

sealed interface CredentialReadResult {
    data object Missing : CredentialReadResult

    data class Available(
        val lease: SecretLease,
    ) : CredentialReadResult {
        override fun toString(): String = "CredentialReadResult.Available(lease=$lease)"
    }

    data class Expired(
        val metadata: CredentialMetadata,
    ) : CredentialReadResult

    data class Corrupt(
        val metadata: CredentialMetadata,
    ) : CredentialReadResult

    data class Unavailable(
        val code: CredentialErrorCode,
    ) : CredentialReadResult
}

sealed interface CredentialWriteResult {
    data class Written(
        val revision: Long,
    ) : CredentialWriteResult

    data class Conflict(
        val currentRevision: Long?,
    ) : CredentialWriteResult

    data class Unavailable(
        val code: CredentialErrorCode,
    ) : CredentialWriteResult
}

sealed interface CredentialDeleteResult {
    data object Deleted : CredentialDeleteResult

    data object AlreadyMissing : CredentialDeleteResult

    data class Unavailable(
        val code: CredentialErrorCode,
    ) : CredentialDeleteResult
}

sealed interface CredentialClearResult {
    data object Cleared : CredentialClearResult

    data class Unavailable(
        val code: CredentialErrorCode,
    ) : CredentialClearResult
}

interface CredentialVault {
    suspend fun read(key: CredentialKey): CredentialReadResult

    suspend fun write(
        key: CredentialKey,
        secret: SecretBytes,
        expiresAt: Instant?,
        condition: WriteCondition,
    ): CredentialWriteResult

    suspend fun delete(key: CredentialKey): CredentialDeleteResult

    suspend fun clear(): CredentialClearResult
}

class DefaultCredentialVault(
    private val store: SealedCredentialStore,
    private val protector: CredentialProtector,
    private val clock: Clock,
    private val diagnostics: CredentialDiagnostics = CredentialDiagnostics.NONE,
) : CredentialVault {
    override suspend fun read(key: CredentialKey): CredentialReadResult =
        safely(
            unavailable = { CredentialReadResult.Unavailable(it) },
        ) {
            when (val stored = store.read(key)) {
                StoreReadResult.Missing -> {
                    diagnose(CredentialDiagnosticCode.READ_MISSING)
                    CredentialReadResult.Missing
                }

                is StoreReadResult.Unavailable -> {
                    diagnose(CredentialDiagnosticCode.READ_UNAVAILABLE)
                    CredentialReadResult.Unavailable(stored.code.safeStoreCode())
                }

                is StoreReadResult.Found -> {
                    readFound(stored.credential)
                }
            }
        }

    override suspend fun write(
        key: CredentialKey,
        secret: SecretBytes,
        expiresAt: Instant?,
        condition: WriteCondition,
    ): CredentialWriteResult =
        safely(
            unavailable = { CredentialWriteResult.Unavailable(it) },
        ) {
            val sealed =
                secret.useBytes { temporary ->
                    SecretBytes.copyOf(temporary).use { operationSecret -> protector.seal(operationSecret) }
                }
            when (sealed) {
                is SealResult.Unavailable -> {
                    diagnose(CredentialDiagnosticCode.WRITE_UNAVAILABLE)
                    CredentialWriteResult.Unavailable(sealed.code.safeProtectorCode())
                }

                is SealResult.Sealed -> {
                    sealed.payload.use { payload ->
                        val expected =
                            when (condition) {
                                WriteCondition.IfAbsent -> null
                                is WriteCondition.IfRevision -> condition.revision
                            }
                        when (val result = store.compareAndSet(key, expected, expiresAt, payload)) {
                            is StoreWriteResult.Applied -> {
                                diagnose(CredentialDiagnosticCode.WRITE_APPLIED)
                                CredentialWriteResult.Written(result.revision)
                            }

                            is StoreWriteResult.Conflict -> {
                                diagnose(CredentialDiagnosticCode.WRITE_CONFLICT)
                                CredentialWriteResult.Conflict(result.currentRevision)
                            }

                            is StoreWriteResult.Unavailable -> {
                                diagnose(CredentialDiagnosticCode.WRITE_UNAVAILABLE)
                                CredentialWriteResult.Unavailable(result.code.safeStoreCode())
                            }
                        }
                    }
                }
            }
        }

    override suspend fun delete(key: CredentialKey): CredentialDeleteResult =
        safely(
            unavailable = { CredentialDeleteResult.Unavailable(it) },
        ) {
            when (val result = store.delete(key)) {
                StoreDeleteResult.Deleted -> {
                    diagnose(CredentialDiagnosticCode.DELETE_APPLIED)
                    CredentialDeleteResult.Deleted
                }

                StoreDeleteResult.Missing -> {
                    diagnose(CredentialDiagnosticCode.DELETE_MISSING)
                    CredentialDeleteResult.AlreadyMissing
                }

                is StoreDeleteResult.Unavailable -> {
                    diagnose(CredentialDiagnosticCode.DELETE_UNAVAILABLE)
                    CredentialDeleteResult.Unavailable(result.code.safeStoreCode())
                }
            }
        }

    override suspend fun clear(): CredentialClearResult =
        safely(
            unavailable = { CredentialClearResult.Unavailable(it) },
        ) {
            when (val result = store.clear()) {
                StoreClearResult.Cleared -> {
                    diagnose(CredentialDiagnosticCode.CLEAR_APPLIED)
                    CredentialClearResult.Cleared
                }

                is StoreClearResult.Unavailable -> {
                    diagnose(CredentialDiagnosticCode.CLEAR_UNAVAILABLE)
                    CredentialClearResult.Unavailable(result.code.safeStoreCode())
                }
            }
        }

    private suspend fun readFound(stored: StoredCredential): CredentialReadResult =
        stored.use {
            if (stored.metadata.expiresAt?.let { it <= clock.instant() } == true) {
                diagnose(CredentialDiagnosticCode.READ_EXPIRED)
                return@use CredentialReadResult.Expired(stored.metadata)
            }

            when (val opened = protector.open(stored.payload)) {
                OpenResult.Corrupt -> {
                    diagnose(CredentialDiagnosticCode.READ_CORRUPT)
                    CredentialReadResult.Corrupt(stored.metadata)
                }

                is OpenResult.Unavailable -> {
                    diagnose(CredentialDiagnosticCode.READ_UNAVAILABLE)
                    CredentialReadResult.Unavailable(opened.code.safeProtectorCode())
                }

                is OpenResult.Opened -> {
                    opened.secret.use { secret ->
                        secret.useBytes { temporary ->
                            diagnose(CredentialDiagnosticCode.READ_AVAILABLE)
                            CredentialReadResult.Available(SecretLease(temporary, stored.metadata))
                        }
                    }
                }
            }
        }

    private suspend fun <T> safely(
        unavailable: (CredentialErrorCode) -> T,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            diagnose(CredentialDiagnosticCode.OPERATION_CANCELLED)
            throw cancelled
        } catch (_: Exception) {
            diagnose(CredentialDiagnosticCode.INTERNAL_FAILURE)
            unavailable(CredentialErrorCode.INTERNAL_FAILURE)
        }

    private fun diagnose(code: CredentialDiagnosticCode) {
        try {
            diagnostics.record(code)
        } catch (_: Exception) {
            // Diagnostics are best-effort observers and never affect vault results or commits.
        }
    }

    private fun CredentialErrorCode.safeStoreCode(): CredentialErrorCode =
        if (this == CredentialErrorCode.STORE_UNAVAILABLE) this else CredentialErrorCode.INTERNAL_FAILURE

    private fun CredentialErrorCode.safeProtectorCode(): CredentialErrorCode =
        if (this == CredentialErrorCode.PROTECTOR_UNAVAILABLE) this else CredentialErrorCode.INTERNAL_FAILURE
}
