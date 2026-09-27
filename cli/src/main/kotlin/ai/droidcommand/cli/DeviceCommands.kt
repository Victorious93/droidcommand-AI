package ai.droidcommand.cli

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.AgentState
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.LogEvent
import ai.droidcommand.agent.LogLevel
import ai.droidcommand.agent.Logger
import ai.droidcommand.agent.Planner
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.describe
import ai.droidcommand.config.EnvConfigSource
import ai.droidcommand.llm.factory.LlmProviderFactory
import ai.droidcommand.remote.JdkHttpTransport
import ai.droidcommand.remote.discovery.DeviceDiscovery
import ai.droidcommand.remote.discovery.DiscoveryResponder
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
 * followed by the result text. While a Forge objective runs, the device may first send any number
 * of [STATUS_PROGRESS] records, one per step; the final [STATUS_OK] or [STATUS_FAILED] record
 * always ends the response.
 */
internal object RemotePilot {
    const val STATUS_OK: Byte = 0
    const val STATUS_FAILED: Byte = 1
    const val STATUS_PROGRESS: Byte = 2
    const val FORGE_MARKER = "@forge"

    fun encodeForgeRequest(objective: String): ByteArray = "$FORGE_MARKER\n$objective".toByteArray()

    fun encodeRequest(toolName: String, inputPairs: List<String>): ByteArray =
        (listOf(toolName) + inputPairs).joinToString("\n").toByteArray()

    fun decodeRequest(bytes: ByteArray): List<String> = bytes.decodeToString().split("\n")

    fun encodeResponse(ok: Boolean, text: String): ByteArray =
        byteArrayOf(if (ok) STATUS_OK else STATUS_FAILED) + text.toByteArray()

    fun encodeProgress(text: String): ByteArray = byteArrayOf(STATUS_PROGRESS) + text.toByteArray()

    /** A decoded record: progress, or the final result ([ok] says whether it succeeded). */
    class Response(val progress: Boolean, val ok: Boolean, val text: String)

    fun decodeResponse(bytes: ByteArray): Response {
        if (bytes.isEmpty()) return Response(progress = false, ok = false, text = "Empty response from device")
        val text = bytes.copyOfRange(1, bytes.size).decodeToString()
        return when (bytes[0]) {
            STATUS_PROGRESS -> Response(progress = true, ok = true, text = text)
            STATUS_OK -> Response(progress = false, ok = true, text = text)
            else -> Response(progress = false, ok = false, text = text)
        }
    }
}

/**
 * Turns one [ai.droidcommand.agent.ObjectiveEngine] log event into a progress line for the
 * controller, or null for events not worth sending. Only the step number, tool name and outcome
 * go out: never tool inputs or exception details, which could carry more than the controller
 * should see.
 */
internal fun describeProgress(event: LogEvent): String? {
    if (event.level == LogLevel.DEBUG) return null
    val step = event.fields["iteration"]?.let { "Step $it: " }.orEmpty()
    return when (event.message) {
        "objective_started" -> "Started"
        "tool_result" -> "${step}ran ${event.fields["tool"]} (${event.fields["outcome"]})"
        "unknown_tool" -> "${step}the planner asked for an unknown tool, ${event.fields["tool"]}"
        "tool_threw" -> "${step}${event.fields["tool"]} threw an error"
        "planner_threw" -> "${step}the planner threw an error"
        "finalization_nudge_sent" -> "${step}${event.fields["remaining"]} step(s) left; asking the planner to finish"
        "objective_completed", "objective_aborted", "objective_cancelled", "objective_exhausted_iterations" -> null
        else -> "$step${event.message}"
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

internal class RunningDeviceServer(
    val server: PairedSocketServer,
    val address: InetSocketAddress,
    val pairing: NewPairing,
    val discovery: DiscoveryResponder? = null,
    val discoveryAddress: InetSocketAddress? = null,
) {
    fun close() {
        discovery?.close()
        server.close()
    }
}

/**
 * Starts a [PairedSocketServer] that runs each request it receives on a fresh session from
 * [sessionFactory], and pairs one controller for it. A fresh session per request matters: an
 * [ai.droidcommand.agent.AgentStateMachine] never leaves a terminal state, so once a Forge
 * objective completed or failed on a shared session, every later request would be refused. A Pilot request runs one tool as [Initiator.REMOTE], so a tool restricted to
 * the device owner stays off-limits. A Forge request runs a whole objective with a planner built on
 * this machine by [plannerFactory] (from this machine's own LLM configuration, never the
 * controller's), sending a progress record after each step. If a progress record can't be
 * delivered because the controller went away, the objective is cancelled before its next step. Either way every tool call goes through the same
 * [ai.droidcommand.security.SecureToolExecutor] gate as a local call, so a sensitive tool still
 * needs approval on this machine's console. A remote controller can't approve its own request.
 * Requests run one at a time.
 *
 * With [discoveryBind] set, a [DiscoveryResponder] also answers LAN discovery probes under
 * [deviceName], pointing at the paired server's port. Discovery reveals only the name and port.
 */
internal fun startDeviceServer(
    sessionFactory: () -> CliSession,
    bind: InetSocketAddress,
    controllerName: String,
    ttl: Duration?,
    plannerFactory: () -> Planner = { LlmProviderFactory.createPlanner(EnvConfigSource(), JdkHttpTransport()) },
    discoveryBind: InetSocketAddress? = null,
    deviceName: String = defaultDeviceName(),
): RunningDeviceServer {
    val registry = PairingRegistry()
    val pairing = registry.pair(controllerName, ttl)
    val dispatcher = RemoteDispatcher(sessionFactory, plannerFactory)
    val server = PairedSocketServer(registry) { connection -> serveConnection(dispatcher, connection) }
    val address = server.start(bind)
    if (discoveryBind == null) return RunningDeviceServer(server, address, pairing)
    val responder = DiscoveryResponder(deviceName, address.port)
    val discoveryAddress = try {
        responder.start(discoveryBind)
    } catch (e: IOException) {
        responder.close()
        server.close()
        throw e
    }
    return RunningDeviceServer(server, address, pairing, responder, discoveryAddress)
}

internal fun defaultDeviceName(): String =
    runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "DroidCommand device"

private class RemoteDispatcher(private val sessionFactory: () -> CliSession, private val plannerFactory: () -> Planner) {
    private var planner: Planner? = null

    @Synchronized
    fun pilot(toolName: String, input: Map<String, String>): ToolResult = try {
        sessionFactory().session.runPilotInstruction(toolName, input, initiator = Initiator.REMOTE)
    } catch (e: Exception) {
        ToolResult.Failure("Error running tool '$toolName': ${e.message}")
    }

    @Synchronized
    fun forge(objective: String, progress: Logger, isCancelled: () -> Boolean): Pair<Boolean, String> {
        val planner = planner ?: try {
            plannerFactory().also { planner = it }
        } catch (e: Exception) {
            return false to "The device could not build an LLM planner from its configuration: ${e.message}"
        }
        val outcome = try {
            val session = sessionFactory().session
            session.switchMode(AgentMode.FORGE)
            session.runForgeObjective(objective, planner, isCancelled = isCancelled, logger = progress)
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
            if (objective.isEmpty()) {
                connection.send(RemotePilot.encodeResponse(false, "No objective given"))
                continue
            }
            val progress = ProgressSender(connection)
            val (ok, text) = dispatcher.forge(objective, progress, progress::controllerGone)
            if (progress.controllerGone()) return
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

/** Sends each step of a running objective to the controller, and notices when it has gone away. */
private class ProgressSender(private val connection: PairedConnection) : Logger {
    @Volatile
    private var gone = false

    fun controllerGone(): Boolean = gone || connection.isClosed

    override fun log(event: LogEvent) {
        if (gone) return
        val line = describeProgress(event) ?: return
        try {
            connection.send(RemotePilot.encodeProgress(line))
        } catch (e: IOException) {
            gone = true
        }
    }
}

/**
 * `device-serve [--bind host:port] [--name <controller name>] [--ttl-minutes <n>] [--discoverable]
 * [--device-name <name>]`. Blocks until the process is killed.
 */
internal fun runDeviceServe(rest: List<String>): Int {
    var bind = InetSocketAddress(InetAddress.getLoopbackAddress(), 0)
    var name = "Controller"
    var ttl: Duration? = null
    var discoverable = false
    var deviceName = defaultDeviceName()
    var i = 0
    while (i < rest.size) {
        when (val arg = rest[i]) {
            "--bind" -> bind = parseHostPort(rest.getOrNull(++i)) ?: return usageError("--bind requires host:port")
            "--name" -> name = rest.getOrNull(++i) ?: return usageError("--name requires a value")
            "--ttl-minutes" -> ttl = rest.getOrNull(++i)?.toLongOrNull()?.takeIf { it > 0 }?.let(Duration::ofMinutes)
                ?: return usageError("--ttl-minutes requires a positive whole number")
            "--discoverable" -> discoverable = true
            "--device-name" -> deviceName = rest.getOrNull(++i)?.takeIf { it.isNotBlank() }
                ?: return usageError("--device-name requires a value")
            else -> return usageError("Unknown device-serve option '$arg'")
        }
        i++
    }
    if (discoverable && bind.address?.isLoopbackAddress != false) {
        return usageError("--discoverable needs --bind to a network address (for example 0.0.0.0:7100); other devices can't reach loopback")
    }
    val discoveryBind = if (discoverable) InetSocketAddress(DeviceDiscovery.DEFAULT_PORT) else null
    val running = try {
        startDeviceServer({ buildSession() }, bind, name, ttl, discoveryBind = discoveryBind, deviceName = deviceName)
    } catch (e: IOException) {
        val where = if (discoverable) "$bind or UDP port ${DeviceDiscovery.DEFAULT_PORT}" else "$bind"
        System.err.println("Could not listen on $where: ${e.message}")
        return 1
    }
    println("Listening on ${running.address.hostString}:${running.address.port}")
    running.discoveryAddress?.let { println("Discoverable as \"$deviceName\" on UDP port ${it.port}") }
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

/**
 * `device-discover [--timeout-ms <n>] [--target host:port ...]`: lists `device-serve --discoverable`
 * instances that answer. Without `--target` it broadcasts on the local network. Exits 1 when none answer.
 */
internal fun runDeviceDiscover(rest: List<String>): Int {
    var timeout = Duration.ofSeconds(2)
    val targets = mutableListOf<InetSocketAddress>()
    var i = 0
    while (i < rest.size) {
        when (val arg = rest[i]) {
            "--timeout-ms" -> timeout = rest.getOrNull(++i)?.toLongOrNull()?.takeIf { it in 1..60_000 }?.let(Duration::ofMillis)
                ?: return usageError("--timeout-ms requires a whole number from 1 to 60000")
            "--target" -> targets += parseHostPort(rest.getOrNull(++i)) ?: return usageError("--target requires host:port")
            else -> return usageError("Unknown device-discover option '$arg'")
        }
        i++
    }
    val found = try {
        DeviceDiscovery.discover(targets.ifEmpty { listOf(DeviceDiscovery.broadcastTarget()) }, timeout)
    } catch (e: IOException) {
        System.err.println("Discovery failed: ${e.message}")
        return 1
    }
    if (found.isEmpty()) {
        System.err.println("No devices answered within ${timeout.toMillis()} ms.")
        return 1
    }
    for (device in found) {
        val host = device.address.address.hostAddress.let { if (':' in it) "[$it]" else it }
        println("$host:${device.address.port}  ${device.name}")
    }
    return 0
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
            printUntilFinal(connection)
        }
    } catch (e: PairingRejectedException) {
        System.err.println("Pairing with $target failed: ${e.message}")
        1
    } catch (e: IOException) {
        System.err.println("Could not reach the device at $target: ${e.message}")
        1
    }
}

/** Prints progress records as they arrive, then the final result. Returns the exit code. */
private fun printUntilFinal(connection: PairedConnection): Int {
    while (true) {
        val reply = connection.receive() ?: return usageError("Device closed the connection without answering")
        val response = RemotePilot.decodeResponse(reply)
        println(if (response.progress) "[device] ${response.text}" else response.text)
        System.out.flush()
        if (!response.progress) return if (response.ok) 0 else 1
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
