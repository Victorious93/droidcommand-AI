package ai.droidcommand.pccompanion

import ai.droidcommand.deviceconnect.DeviceConnect
import ai.droidcommand.deviceconnect.DeviceConnectDecision
import ai.droidcommand.deviceconnect.DeviceConnectService
import ai.droidcommand.deviceconnect.DeviceConnectTransport
import ai.droidcommand.deviceconnect.DeviceKind
import ai.droidcommand.deviceconnect.FileDeviceConnectStore
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Minimal PC side of "Connect a device". Same SPAKE2 pairing as the
 * Android app, then ping / status only. See docs/DEVICE_CONNECT.md.
 */
fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] in listOf("-h", "--help", "help")) {
        printHelp()
        return
    }
    val home = Path.of(System.getProperty("user.home"), ".droidcommand", "device-connect.json")
    val service = DeviceConnectService(FileDeviceConnectStore(home))
    service.identity()
    when (args[0]) {
        "listen" -> listen(service, args.drop(1))
        "join" -> join(service, args.drop(1))
        "revoke" -> {
            val id = args.getOrNull(1) ?: fail("revoke needs a device id")
            if (service.revoke(id)) println("Forgot $id.") else fail("No paired device $id.")
        }
        "list" -> {
            val peers = service.peers()
            if (peers.isEmpty()) println("No paired devices.")
            peers.forEach { println("${it.deviceId}  ${it.displayName}  ${it.kind}") }
        }
        else -> fail("Unknown command ${args[0]}. Try listen, join, list, or revoke.")
    }
}

private fun listen(service: DeviceConnectService, rest: List<String>) {
    val password = rest.firstOrNull { !it.startsWith("-") } ?: DeviceConnect.generatePassword()
    val port = rest.find { it.startsWith("--port=") }?.removePrefix("--port=")?.toIntOrNull()
        ?: DeviceConnect.DEFAULT_PORT
    val transport = DeviceConnectTransport(
        service = service,
        localKind = DeviceKind.PC,
        onVerify = { code, name ->
            println("Check the code on the other device. This computer shows: $code ($name)")
            print("Do both codes match? [y/N] ")
            readln().trim().equals("y", ignoreCase = true)
        },
    )
    transport.preparePassword(password)
    val bound = transport.startListening(port)
    val host = transport.localAddresses().firstOrNull() ?: "127.0.0.1"
    println("Waiting for a phone on this computer.")
    println("Address: $host:$bound")
    println("Invite: ${DeviceConnect.inviteOf(host, bound, service.identity().deviceId)}")
    println("One-time password: $password")
    println("Leave this window open. Ctrl+C stops listening.")
    Runtime.getRuntime().addShutdownHook(Thread { transport.close() })
    while (true) {
        Thread.sleep(60_000)
    }
}

private fun join(service: DeviceConnectService, rest: List<String>) {
    val target = rest.firstOrNull() ?: fail("join needs host:port or an invite string")
    val password = rest.getOrNull(1) ?: run {
        print("One-time password: ")
        readln()
    }
    val (host, port) = DeviceConnect.parseInvite(target)?.let { it.first to it.second }
        ?: target.split(':').let { parts ->
            if (parts.size != 2) fail("Use host:port or a dca-connect invite.")
            parts[0] to (parts[1].toIntOrNull() ?: fail("Port must be a number."))
        }
    val transport = DeviceConnectTransport(
        service = service,
        localKind = DeviceKind.PC,
        onVerify = { code, name ->
            println("Check the code on the other device. This computer shows: $code ($name)")
            print("Do both codes match? [y/N] ")
            readln().trim().equals("y", ignoreCase = true)
        },
    )
    when (val result = transport.join(host, port, password)) {
        is DeviceConnectDecision.Ok -> {
            println(result.message)
            val peer = service.peers().lastOrNull() ?: return
            println(transport.ping(peer.deviceId).let { if (it is DeviceConnectDecision.Ok) it.message else (it as DeviceConnectDecision.Refused).reason })
            println(transport.remoteStatus(peer.deviceId).let { if (it is DeviceConnectDecision.Ok) it.message else (it as DeviceConnectDecision.Refused).reason })
        }
        is DeviceConnectDecision.Refused -> fail(result.reason)
    }
    transport.close()
}

private fun printHelp() {
    println(
        """
        DroidCommand PC companion — pair a phone on the same Wi-Fi, then ping it.

        ./gradlew :pc-companion:run --args="listen"
        ./gradlew :pc-companion:run --args="listen MyPassword --port=17890"
        ./gradlew :pc-companion:run --args="join 192.168.1.8:17890 MyPassword"
        ./gradlew :pc-companion:run --args="list"
        ./gradlew :pc-companion:run --args="revoke <device-id>"

        Pairing uses a one-time password. The password is not sent in the clear.
        After pairing this tool only asks "are you there?" and reads a short status.
        It cannot run commands on the phone.
        """.trimIndent(),
    )
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(1)
}
