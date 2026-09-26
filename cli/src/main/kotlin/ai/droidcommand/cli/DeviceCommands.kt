package ai.droidcommand.cli

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.AgentState
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.Planner
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.describe
import ai.droidcommand.config.EnvConfigSource
import ai.droidcommand.llm.factory.LlmProviderFactory
import ai.droidcommand.remote.JdkHttpTransport
import ai.droidcommand.remote.pairing.NewPairing
import ai.droidcommand.remote.pairing.PairedConnection
import ai.droidcommand.remote.pairing.PairedSocketClient
import ai.droidcommand.remote.pairing.PairedSocketServer
import ai.droidcommand.remote.pairing.PairingRegistry
import ai.droidcommand.remote.pairing.PairingRejectedException
import ai.droidcommand.remote.pairing.PairingSecret
import java.io.IOException
import java.io.PrintStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration

/**
 * The remote request format carried inside each encrypted record. A Pilot request is the tool
 * name and its `key=value` inputs, one per line (so a value may contain spaces). A Forge request
 * is the line [FORGE_MARKER] followed by the objective text; tool names never start with `@`, so
 * the two can't be confused. A response is one status byte ([STATUS_OK] or [STATUS_FAILED])
 * followed by the result text.
 */
internal object RemotePilot {
    const val STATUS_OK: Byte = 0
    const val STATUS_FAILED: Byte = 1
    const val FORGE_MARKER = "@forge"

    fun encodeForgeRequest(objective: String): ByteArray = "$FORGE_MARKER\n$objective".toByteArray()

    fun encodeRequest(toolName: String, inputPairs: List<String>): ByteArray =
        (listOf(toolName) + inputPairs).joinToString("\n").toByteArray()

    fun decodeRequest(bytes: ByteArray): List<String> = bytes.decodeToString().split("\n")

    fun encodeResponse(ok: Boolean, text: String): ByteArray =
        byteArrayOf(if (ok) STATUS_OK else STATUS_FAILED) + text.toByteArray()

    fun decodeResponse(bytes: ByteArray): Pair<Boolean, String> {
        if (bytes.isEmpty()) return false to "Empty response from device"
        return (bytes[0] == STATUS_OK) to bytes.copyOfRange(1, bytes.size).decodeToString()
    }
}

/** Parses `key=value` pairs, as `pilot` takes them. Returns null and prints why on the first malformed one. */
internal fun parseInputPairs(pairs: List<String>, err: PrintStream = System.err): Map<String, String>? {
    val input = mutableMapOf<String, String>()
    for (pair in pairs) {
        val separator = pair.indexOf('=')
        if (separator < 0) {
            err.println("Invalid input '$pair'; expected key=value")
            return null
        }
        input[pair.substring(0, separator)] = pair.substring(separator + 1)
    }
    return input
}

internal class RunningDeviceServer(val server: PairedSocketServer, val address: InetSocketAddress, val pairing: NewPairing)

/**
 * Starts a [PairedSocketServer] that runs each request it receives on a fresh session from
 * [sessionFactory], and pairs one controller for it. A fresh session per request matters: an
 * [ai.droidcommand.agent.AgentStateMachine] never leaves a terminal state, so once a Forge
 * objective completed or failed on a shared session, every later request would be refused. A Pilot request runs one tool as [Initiator.REMOTE], so a tool restricted to
 * the device owner stays off-limits. A Forge request runs a whole objective with a planner built on
 * this machine by [plannerFactory] (from this machine's own LLM configuration, never the
 * controller's). Either way every tool call goes through the same
 * [ai.droidcommand.security.SecureToolExecutor] gate as a local call, so a sensitive tool still
 * needs approval on this machine's console. A remote controller can't approve its own request.
 * Requests run one at a time.
 */
internal fun startDeviceServer(
    sessionFactory: () -> CliSession,
    bind: InetSocketAddress,
    controllerName: String,
    ttl: Duration?,
    plannerFactory: () -> Planner = { LlmProviderFactory.createPlanner(EnvConfigSource(), JdkHttpTransport()) },
): RunningDeviceServer {
    val registry = PairingRegistry()
    val pairing = registry.pair(controllerName, ttl)
    val dispatcher = RemoteDispatcher(sessionFactory, plannerFactory)
    val server = PairedSocketServer(registry) { connection -> serveConnection(dispatcher, connection) }
    val address = server.start(bind)
    return RunningDeviceServer(server, address, pairing)
}

private class RemoteDispatcher(private val sessionFactory: () -> CliSession, private val plannerFactory: () -> Planner) {
    private var planner: Planner? = null

    @Synchronized
    fun pilot(toolName: String, input: Map<String, String>): ToolResult = try {
        sessionFactory().session.runPilotInstruction(toolName, input, initiator = Initiator.REMOTE)
    } catch (e: Exception) {
        ToolResult.Failure("Error running tool '$toolName': ${e.message}")
    }

    @Synchronized
    fun forge(objective: String): Pair<Boolean, String> {
        val planner = planner ?: try {
            plannerFactory().also { planner = it }
        } catch (e: Exception) {
            return false to "The device could not build an LLM planner from its configuration: ${e.message}"
        }
        val outcome = try {
            val session = sessionFactory().session
            session.switchMode(AgentMode.FORGE)
            session.runForgeObjective(objective, planner)
        } catch (e: Exception) {
            return false to "Error running objective: ${e.message}"
        }
        return (outcome.finalState is AgentState.Completed) to
            "Final state: ${outcome.finalState}\nIterations: ${outcome.iterations}"
    }
}

private fun serveConnection(dispatcher: RemoteDispatcher, connection: PairedConnection) {
    while (true) {
        val request = connection.receive() ?: return
        val lines = RemotePilot.decodeRequest(request)
        if (lines.first() == RemotePilot.FORGE_MARKER) {
            val objective = lines.drop(1).joinToString("\n").trim()
            val (ok, text) = if (objective.isEmpty()) false to "No objective given" else dispatcher.forge(objective)
            connection.send(RemotePilot.encodeResponse(ok, text))
            continue
        }
        val toolName = lines.first()
        val errors = java.io.ByteArrayOutputStream()
        val input = parseInputPairs(lines.drop(1).filter { it.isNotEmpty() }, PrintStream(errors, true, Charsets.UTF_8))
        val response = when {
            toolName.isBlank() -> RemotePilot.encodeResponse(false, "No tool name given")
            input == null -> RemotePilot.encodeResponse(false, errors.toString(Charsets.UTF_8).trim())
            else -> {
                val result = dispatcher.pilot(toolName, input)
                RemotePilot.encodeResponse(result !is ToolResult.Failure, result.describe())
            }
        }
        connection.send(response)
    }
}

/** `device-serve [--bind host:port] [--name <controller name>] [--ttl-minutes <n>]`. Blocks until the process is killed. */
internal fun runDeviceServe(rest: List<String>): Int {
    var bind = InetSocketAddress(InetAddress.getLoopbackAddress(), 0)
    var name = "Controller"
    var ttl: Duration? = null
    var i = 0
    while (i < rest.size) {
        when (val arg = rest[i]) {
            "--bind" -> bind = parseHostPort(rest.getOrNull(++i)) ?: return usageError("--bind requires host:port")
            "--name" -> name = rest.getOrNull(++i) ?: return usageError("--name requires a value")
            "--ttl-minutes" -> ttl = rest.getOrNull(++i)?.toLongOrNull()?.takeIf { it > 0 }?.let(Duration::ofMinutes)
                ?: return usageError("--ttl-minutes requires a positive whole number")
            else -> return usageError("Unknown device-serve option '$arg'")
        }
        i++
    }
    val running = try {
        startDeviceServer({ buildSession() }, bind, name, ttl)
    } catch (e: IOException) {
        System.err.println("Could not listen on $bind: ${e.message}")
        return 1
    }
    println("Listening on ${running.address.hostString}:${running.address.port}")
    println("Controller id: ${running.pairing.device.id}")
    println("Pairing secret (shown once; pass it to the controller as $SECRET_ENV): ${running.pairing.secret.encode()}")
    running.pairing.device.expiresAt?.let { println("Pairing expires at $it") }
    System.out.flush()
    Thread.currentThread().join()
    return 0
}

/**
 * `device-send <host:port> <controller id> <tool> [key=value ...]`. The secret comes from the
 * [SECRET_ENV] environment variable, never an argument, so it stays out of shell history and the
 * process list.
 */
internal fun runDeviceSend(rest: List<String>, secretSource: (String) -> String? = System::getenv): Int {
    if (rest.size < 3) {
        return usageError("Usage: device-send <host:port> <controller id> <tool> [key=value ...]")
    }
    val address = parseHostPort(rest[0]) ?: return usageError("Expected host:port, got '${rest[0]}'")
    val controllerId = rest[1]
    val toolName = rest[2]
    val inputPairs = rest.drop(3)
    parseInputPairs(inputPairs) ?: return 1
    return sendToDevice(rest[0], address, controllerId, RemotePilot.encodeRequest(toolName, inputPairs), secretSource)
}

/**
 * `device-forge <host:port> <controller id> <objective...>`: runs a whole Forge objective on the
 * device, planned by the device's own LLM configuration. Same secret handling as [runDeviceSend].
 */
internal fun runDeviceForge(rest: List<String>, secretSource: (String) -> String? = System::getenv): Int {
    if (rest.size < 3) {
        return usageError("Usage: device-forge <host:port> <controller id> <objective...>")
    }
    val address = parseHostPort(rest[0]) ?: return usageError("Expected host:port, got '${rest[0]}'")
    val objective = rest.drop(2).joinToString(" ")
    return sendToDevice(rest[0], address, rest[1], RemotePilot.encodeForgeRequest(objective), secretSource)
}

private fun sendToDevice(
    target: String,
    address: InetSocketAddress,
    controllerId: String,
    request: ByteArray,
    secretSource: (String) -> String?,
): Int {
    val encodedSecret = secretSource(SECRET_ENV) ?: return usageError("Set $SECRET_ENV to the pairing secret device-serve printed")
    val secret = try {
        PairingSecret.decode(encodedSecret)
    } catch (e: IllegalArgumentException) {
        return usageError("$SECRET_ENV is not a valid pairing secret")
    }
    return try {
        PairedSocketClient.connect(address, controllerId, secret).use { connection ->
            connection.send(request)
            val reply = connection.receive() ?: return usageError("Device closed the connection without answering")
            val (ok, text) = RemotePilot.decodeResponse(reply)
            println(text)
            if (ok) 0 else 1
        }
    } catch (e: PairingRejectedException) {
        System.err.println("Pairing with $target failed: ${e.message}")
        1
    } catch (e: IOException) {
        System.err.println("Could not reach the device at $target: ${e.message}")
        1
    }
}

internal const val SECRET_ENV = "DROIDCOMMAND_PAIRING_SECRET"

internal fun parseHostPort(value: String?): InetSocketAddress? {
    if (value == null) return null
    val separator = value.lastIndexOf(':')
    if (separator <= 0) return null
    val port = value.substring(separator + 1).toIntOrNull()?.takeIf { it in 0..65535 } ?: return null
    return InetSocketAddress(value.substring(0, separator).removePrefix("[").removeSuffix("]"), port)
}
