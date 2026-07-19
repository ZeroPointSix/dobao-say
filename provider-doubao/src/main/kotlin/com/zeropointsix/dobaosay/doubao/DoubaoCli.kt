package com.zeropointsix.dobaosay.doubao

import com.zeropointsix.dobaosay.asr.AsrCommandResult
import com.zeropointsix.dobaosay.asr.AsrEvent
import com.zeropointsix.dobaosay.asr.AsrFailure
import com.zeropointsix.dobaosay.asr.AsrSessionConfig
import com.zeropointsix.dobaosay.asr.AsrSessionState
import com.zeropointsix.dobaosay.asr.AudioFormat
import com.zeropointsix.dobaosay.asr.DefaultAsrSession
import com.zeropointsix.dobaosay.asr.SessionOutcome
import com.zeropointsix.dobaosay.audio.Pcm16Framer
import com.zeropointsix.dobaosay.audio.TailFramePolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) {
    val code =
        try {
            runBlocking { DoubaoCli.run(args.toList()) }
        } catch (error: IllegalArgumentException) {
            System.err.println("error: ${error.message}")
            DoubaoCli.printUsage()
            2
        } catch (error: Exception) {
            System.err.println("error: ${error.message}")
            1
        }
    if (code != 0) exitProcess(code)
}

object DoubaoCli {
    suspend fun run(args: List<String>): Int {
        if (args.isEmpty() || args.first() in setOf("-h", "--help")) {
            printUsage()
            return 0
        }
        return when (args.first()) {
            "register" -> register(parseOptions(args.drop(1)))
            "transcribe" -> transcribe(args.drop(1))
            else -> throw IllegalArgumentException("unknown command: ${args.first()}")
        }
    }

    fun printUsage() {
        println(
            """
            Usage:
              provider-doubao register [--credential-path PATH]
              provider-doubao transcribe WAV [--credential-path PATH] [--device-id ID] [--token TOKEN] [--realtime]

            Environment:
              DOUBAO_CREDENTIAL_PATH, DOUBAO_DEVICE_ID, DOUBAO_TOKEN
            """.trimIndent(),
        )
    }

    private suspend fun register(options: Map<String, String?>): Int {
        val credentialPath = credentialPath(options)
        val client = DoubaoDeviceClient()
        val registered = client.registerDevice()
        val token = client.fetchAsrToken(registered.deviceId, registered.cdid)
        val credentials = registered.withToken(token)
        if (credentialPath != null) DoubaoCredentialFile.write(credentialPath, credentials)

        println("registered device_id=${credentials.deviceId.redactedSecret()}")
        println("install_id=${credentials.installId.redactedSecret()}")
        println("token=${credentials.token.redactedSecret()}")
        credentialPath?.let { println("saved_credentials=$it") }
        return 0
    }

    private suspend fun transcribe(args: List<String>): Int {
        require(args.isNotEmpty()) { "transcribe requires a WAV path" }
        val wavPath = Path(args.first())
        val options = parseOptions(args.drop(1))
        val credentialPath = credentialPath(options)
        val savedCredentials = credentialPath?.let { DoubaoCredentialFile.read(it) }
        val providerConfig =
            DoubaoProviderConfig(
                credentials = savedCredentials,
                deviceId = optionOrEnv(options, "device-id", "DOUBAO_DEVICE_ID"),
                token = optionOrEnv(options, "token", "DOUBAO_TOKEN"),
            )
        val credentials = providerConfig.deviceClient().ensureCredentials(providerConfig)
        if (credentialPath != null) DoubaoCredentialFile.write(credentialPath, credentials)

        val audioFormat = AudioFormat(sampleRateHz = 16_000, channels = 1, frameDurationMs = 20)
        val wav = WavPcmReader.read(wavPath, audioFormat)
        val frames =
            Pcm16Framer(wav.audioFormat, TailFramePolicy.PAD_WITH_ZERO)
                .let { framer -> framer.push(wav.pcm) + framer.finish() }

        println("frames=${frames.size}")
        val sessionConfig = AsrSessionConfig(audioFormat = audioFormat, connectTimeout = 20.seconds)
        val provider = DoubaoAsrProvider(providerConfig.copy(credentials = credentials, token = credentials.token))
        val session = DefaultAsrSession(sessionConfig, provider.createDriver(sessionConfig))
        val ready = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<SessionOutcome>()

        val collector =
            CoroutineScope(Dispatchers.Default).launch {
                session.events.collect { event ->
                    when (event) {
                        is AsrEvent.Ready -> {
                            ready.complete(Unit)
                        }

                        is AsrEvent.SpeechStarted -> {
                            println("[speech-started]")
                        }

                        is AsrEvent.Partial -> {
                            println("[partial] ${event.text}")
                        }

                        is AsrEvent.Final -> {
                            println("[final] ${event.text}")
                        }

                        is AsrEvent.SpeechEnded -> {
                            println("[speech-ended]")
                        }

                        is AsrEvent.Error -> {
                            val detail =
                                when (val failure = event.failure) {
                                    is AsrFailure.ProtocolViolation -> failure.diagnosticCode
                                    is AsrFailure.Internal -> failure.diagnosticCode
                                    else -> failure.toString()
                                }
                            println("[error] ${event.failure.code} detail=$detail")
                        }

                        is AsrEvent.Closed -> {
                            closed.complete(event.outcome)
                        }

                        is AsrEvent.Connecting,
                        is AsrEvent.Retrying,
                        -> {
                        }
                    }
                }
            }

        require(session.start() == AsrCommandResult.Accepted) { "session did not start" }
        withTimeout(20.seconds) { ready.await() }
        for (frame in frames) {
            val result = session.pushAudio(frame)
            if (result != AsrCommandResult.Accepted) break
            if (options.containsKey("realtime")) delay(audioFormat.frameDurationMs.toLong())
        }
        // Give the provider a moment to deliver early partials before requesting stop on file mode.
        if (!options.containsKey("realtime")) delay(100)
        session.stop()
        val outcome = withTimeout(60.seconds) { closed.await() }
        collector.cancel()
        if (outcome is SessionOutcome.Succeeded) {
            println(outcome.text)
            return 0
        }
        if (session.snapshot.value.state is AsrSessionState.Closed) return 1
        return 1
    }

    private fun parseOptions(args: List<String>): Map<String, String?> {
        val options = linkedMapOf<String, String?>()
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            require(arg.startsWith("--")) { "unexpected positional argument: $arg" }
            val name = arg.removePrefix("--")
            if (name == "realtime") {
                options[name] = null
                index += 1
            } else {
                require(index + 1 < args.size) { "missing value for --$name" }
                options[name] = args[index + 1]
                index += 2
            }
        }
        return options
    }

    private fun credentialPath(options: Map<String, String?>): Path? =
        optionOrEnv(options, "credential-path", "DOUBAO_CREDENTIAL_PATH")?.let(::Path)

    private fun optionOrEnv(
        options: Map<String, String?>,
        name: String,
        envName: String,
    ): String? = options[name] ?: System.getenv(envName)?.takeIf { it.isNotBlank() }
}
