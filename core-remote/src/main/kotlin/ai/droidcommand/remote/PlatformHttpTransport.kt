package ai.droidcommand.remote

/**
 * Picks the HTTP transport that can actually run on this runtime.
 *
 * [JdkHttpTransport] needs `java.net.http`, which Android does not ship.
 * [HttpUrlConnectionTransport] runs on both a desktop JVM and Android, but
 * has no certificate pinning or mutual TLS — those stay on
 * [JdkHttpTransport] for JVM callers that need them.
 *
 * `:app` already injects [HttpUrlConnectionTransport] explicitly (see
 * `AppModule.provideHttpTransport`). This factory is the documented
 * default for new JVM/Android call sites that should not hard-code a
 * class Android cannot load.
 */
object PlatformHttpTransport {
    fun runningOnAndroid(): Boolean =
        runCatching { Class.forName("android.os.Build") }.isSuccess

    fun create(): StreamingHttpTransport =
        if (runningOnAndroid()) HttpUrlConnectionTransport() else JdkHttpTransport()
}
