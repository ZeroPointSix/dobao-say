package com.zeropointsix.dobaosay.credential

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class CredentialVaultConcurrencyTest {
    @Test
    fun `real scheduler concurrent if absent has one winner`() =
        runBlocking {
            val vault =
                DefaultCredentialVault(
                    InMemorySealedCredentialStore(),
                    TestProtector(),
                    Clock.systemUTC(),
                )
            val key = CredentialKey.of("synthetic.race")
            val start = CompletableDeferred<Unit>()

            val results =
                withTimeout(10.seconds) {
                    List(128) { value ->
                        async(Dispatchers.Default) {
                            start.await()
                            SecretBytes.copyOf(byteArrayOf(value.toByte())).use {
                                vault.write(key, it, null, WriteCondition.IfAbsent)
                            }
                        }
                    }.also { start.complete(Unit) }.awaitAll()
                }

            assertEquals(1, results.count { it is CredentialWriteResult.Written })
            assertEquals(127, results.count { it is CredentialWriteResult.Conflict })
            assertIs<CredentialReadResult.Available>(vault.read(key)).lease.close()
        }
}
