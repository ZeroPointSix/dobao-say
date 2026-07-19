package com.zeropointsix.dobaosay.credential

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CredentialVaultTest {
    private val key = CredentialKey.of("synthetic.account")
    private val now = Instant.parse("2026-07-19T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `public secret path accepts bytes and redacts every value string`() = runTest {
        val input = syntheticSecret()
        val expected = input.copyOf()
        val secret = SecretBytes.copyOf(input)
        input.fill(0)
        val vault = vault()

        val written = vault.write(key, secret, null, WriteCondition.IfAbsent)
        secret.close()
        secret.close()
        val available = assertIs<CredentialReadResult.Available>(vault.read(key))

        assertContentEquals(expected, available.lease.useBytes { it.copyOf() })
        val exposed = available.lease.useBytes { it }
        assertTrue(exposed.all { it == 0.toByte() })
        assertNoSecret(written, available, available.lease, secret)
        assertFalse(CredentialVault::class.java.methods.any { method ->
            method.name == "write" && method.parameterTypes.any { it == String::class.java }
        })
        available.lease.close()
        available.lease.close()
        assertFailsWith<IllegalStateException> { available.lease.useBytes { it.size } }
    }

    @Test
    fun `missing expired at boundary corrupt and unavailable are distinct`() = runTest {
        val store = InMemorySealedCredentialStore()
        val protector = TestProtector()
        val vault = vault(store, protector)

        assertSame(CredentialReadResult.Missing, vault.read(key))
        write(vault, expiresAt = now)
        assertIs<CredentialReadResult.Expired>(vault.read(key))

        val futureKey = CredentialKey.of("synthetic.future")
        write(vault, futureKey, expiresAt = now.plusSeconds(1))
        protector.corruptOnOpen = true
        assertIs<CredentialReadResult.Corrupt>(vault.read(futureKey))
        protector.corruptOnOpen = false
        protector.openUnavailable = true
        assertEquals(
            CredentialReadResult.Unavailable(CredentialErrorCode.PROTECTOR_UNAVAILABLE),
            vault.read(futureKey),
        )
        protector.openUnavailable = false
        store.failReads = true
        assertEquals(
            CredentialReadResult.Unavailable(CredentialErrorCode.STORE_UNAVAILABLE),
            vault.read(futureKey),
        )
    }

    @Test
    fun `seal and store failure preserve previous readable revision`() = runTest {
        val store = InMemorySealedCredentialStore()
        val protector = TestProtector()
        val vault = vault(store, protector)
        val first = assertIs<CredentialWriteResult.Written>(write(vault))

        protector.sealUnavailable = true
        assertEquals(
            CredentialWriteResult.Unavailable(CredentialErrorCode.PROTECTOR_UNAVAILABLE),
            write(vault, bytes = byteArrayOf(9), condition = WriteCondition.IfRevision(first.revision)),
        )
        protector.sealUnavailable = false
        assertContentEquals(syntheticSecret(), readBytes(vault))

        store.failWrites = true
        assertEquals(
            CredentialWriteResult.Unavailable(CredentialErrorCode.STORE_UNAVAILABLE),
            write(vault, bytes = byteArrayOf(8), condition = WriteCondition.IfRevision(first.revision)),
        )
        store.failWrites = false
        assertContentEquals(syntheticSecret(), readBytes(vault))
    }

    @Test
    fun `revision conflict is observable and does not overwrite current value`() = runTest {
        val vault = vault()
        val first = assertIs<CredentialWriteResult.Written>(write(vault))
        val second = assertIs<CredentialWriteResult.Written>(
            write(vault, bytes = byteArrayOf(2), condition = WriteCondition.IfRevision(first.revision)),
        )
        val conflict = write(vault, bytes = byteArrayOf(3), condition = WriteCondition.IfRevision(first.revision))

        assertEquals(CredentialWriteResult.Conflict(second.revision), conflict)
        assertContentEquals(byteArrayOf(2), readBytes(vault))
    }

    @Test
    fun `concurrent if absent has exactly one winner`() = runTest {
        val vault = vault()

        val results = (0 until 64).map { value ->
            async { write(vault, bytes = byteArrayOf(value.toByte())) }
        }.awaitAll()

        assertEquals(1, results.count { it is CredentialWriteResult.Written })
        assertEquals(63, results.count { it is CredentialWriteResult.Conflict })
        assertEquals(1, readBytes(vault).size)
    }

    @Test
    fun `delete clear and lease close are idempotent`() = runTest {
        val vault = vault()
        write(vault)
        val lease = assertIs<CredentialReadResult.Available>(vault.read(key)).lease

        assertSame(CredentialDeleteResult.Deleted, vault.delete(key))
        assertSame(CredentialDeleteResult.AlreadyMissing, vault.delete(key))
        write(vault)
        assertSame(CredentialClearResult.Cleared, vault.clear())
        assertSame(CredentialClearResult.Cleared, vault.clear())
        assertSame(CredentialReadResult.Missing, vault.read(key))
        lease.close()
        lease.close()
    }

    @Test
    fun `underlying exception marker never reaches results diagnostics or strings`() = runTest {
        val marker = syntheticSecret().decodeToString()
        val events = mutableListOf<CredentialDiagnosticCode>()
        val protector = TestProtector().apply { throwOnSeal = IllegalStateException(marker) }
        val vault = vault(protector = protector, diagnostics = CredentialDiagnostics(events::add))

        val result = write(vault)

        assertEquals(CredentialWriteResult.Unavailable(CredentialErrorCode.INTERNAL_FAILURE), result)
        assertTrue(events.isNotEmpty())
        (events + result).forEach { assertFalse(it.toString().contains(marker)) }
    }

    @Test
    fun `cancelling seal leaves no value and job completes`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val protector = TestProtector().apply {
            sealEntered = entered
            sealGate = gate
        }
        val vault = vault(protector = protector)
        val operation = async { write(vault) }
        entered.await()

        operation.cancelAndJoin()

        assertTrue(operation.isCancelled)
        assertSame(CredentialReadResult.Missing, vault.read(key))
    }

    @Test
    fun `cancelling before store commit preserves old version`() = runTest {
        val store = InMemorySealedCredentialStore()
        val vault = vault(store = store)
        val first = assertIs<CredentialWriteResult.Written>(write(vault))
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        store.writeEntered = entered
        store.writeGate = gate
        val replacement = async {
            write(vault, bytes = byteArrayOf(7), condition = WriteCondition.IfRevision(first.revision))
        }
        entered.await()

        replacement.cancelAndJoin()

        assertTrue(replacement.isCancelled)
        store.writeGate = null
        assertContentEquals(syntheticSecret(), readBytes(vault))
    }

    @Test
    fun `restricted keys reject unsafe or unbounded forms`() {
        assertEquals("safe.key-1", CredentialKey.of("safe.key-1").value)
        listOf("", "Upper", "space key", "a/route", "a".repeat(65)).forEach {
            assertFailsWith<IllegalArgumentException> { CredentialKey.of(it) }
        }
    }

    @Test
    fun `public scoped byte access wipes temporary arrays`() = runTest {
        val secret = SecretBytes.copyOf(syntheticSecret())
        val payload = ProtectedPayload.copyOf(byteArrayOf(4, 5, 6))
        lateinit var secretTemporary: ByteArray
        lateinit var payloadTemporary: ByteArray

        secret.useBytes { secretTemporary = it }
        payload.useBytes { payloadTemporary = it }

        assertTrue(secretTemporary.all { it == 0.toByte() })
        assertTrue(payloadTemporary.all { it == 0.toByte() })
        assertTrue(SecretBytes::class.java.methods.any { it.name == "useBytes" })
        assertTrue(ProtectedPayload::class.java.methods.any { it.name == "useBytes" })
        secret.close()
        payload.close()
    }

    @Test
    fun `throwing diagnostics never changes committed business results`() = runTest {
        val diagnostics = CredentialDiagnostics { throw IllegalStateException("observer failed") }
        val vault = vault(diagnostics = diagnostics)

        val written = assertIs<CredentialWriteResult.Written>(write(vault))
        val available = assertIs<CredentialReadResult.Available>(vault.read(key))
        assertContentEquals(syntheticSecret(), available.lease.use { it.useBytes { bytes -> bytes.copyOf() } })
        assertTrue(written.revision > 0)
        assertSame(CredentialDeleteResult.Deleted, vault.delete(key))
        assertSame(CredentialDeleteResult.AlreadyMissing, vault.delete(key))
        write(vault)
        assertSame(CredentialClearResult.Cleared, vault.clear())
        assertSame(CredentialReadResult.Missing, vault.read(key))
    }

    private fun vault(
        store: InMemorySealedCredentialStore = InMemorySealedCredentialStore(),
        protector: TestProtector = TestProtector(),
        diagnostics: CredentialDiagnostics = CredentialDiagnostics.NONE,
    ): CredentialVault = DefaultCredentialVault(store, protector, clock, diagnostics)

    private suspend fun write(
        vault: CredentialVault,
        key: CredentialKey = this.key,
        bytes: ByteArray = syntheticSecret(),
        expiresAt: Instant? = null,
        condition: WriteCondition = WriteCondition.IfAbsent,
    ): CredentialWriteResult = SecretBytes.copyOf(bytes).use { vault.write(key, it, expiresAt, condition) }

    private suspend fun readBytes(vault: CredentialVault, key: CredentialKey = this.key): ByteArray {
        val available = assertIs<CredentialReadResult.Available>(vault.read(key))
        return available.lease.use { lease -> lease.useBytes { it.copyOf() } }
    }

    private fun syntheticSecret(): ByteArray = "SYNTHETIC_SECRET_MARKER_122".encodeToByteArray()

    private fun assertNoSecret(vararg values: Any) {
        val marker = syntheticSecret().decodeToString()
        values.forEach { assertFalse(it.toString().contains(marker), it::class.simpleName) }
    }
}
