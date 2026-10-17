package ai.droidcommand.metasploit

/**
 * The "no blank / no mass / no subnet" target rule this module already
 * documented on [MetasploitCommand]: one authorized host per invocation.
 *
 * Rejects CIDR (`10.0.0.0/24`), comma-lists, wildcards, IPv4 ranges
 * (`10.0.0.1-254`), and unspecified addresses (`0.0.0.0`). A hyphenated
 * DNS label (`my-host.example.com`) is still allowed.
 */
object AuthorizedTarget {
    private val IPV4 = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
    private val IPV4_RANGE = Regex("""^(\d{1,3}\.){3}\d{1,3}-\d{1,3}(\.\d{1,3}){0,3}$""")
    private val HOSTNAME = Regex(
        """^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$""",
    )
    private val IPV6 = Regex("""^[0-9A-Fa-f:]+$""")
    private val MASS_ADDRESSES = setOf("0.0.0.0", "255.255.255.255", "::", "::/0")

    fun requireSingleHost(host: String): String {
        val trimmed = host.trim()
        require(trimmed.isNotEmpty()) {
            "target host must not be blank — this tool never runs against an unspecified/mass target"
        }
        require(trimmed.none { it.isWhitespace() }) {
            "target host must be a single host, not a list"
        }
        require(',' !in trimmed) {
            "target host must be a single host, not a comma-separated list"
        }
        require('/' !in trimmed) {
            "target host must not be a subnet/CIDR"
        }
        require('*' !in trimmed) {
            "target host must not be a wildcard/mass target"
        }
        require(!IPV4_RANGE.matches(trimmed)) {
            "target host must not be an address range"
        }
        require(trimmed !in MASS_ADDRESSES) {
            "target host '$trimmed' is a mass/unspecified address"
        }
        MetasploitCommand.assertSafeInterpolationValue("target.host", trimmed)
        val ok = IPV4.matches(trimmed) || (':' in trimmed && IPV6.matches(trimmed)) || HOSTNAME.matches(trimmed)
        require(ok) { "target host '$trimmed' is not a valid single hostname or IP" }
        if (IPV4.matches(trimmed)) {
            val octets = trimmed.split('.').map { it.toInt() }
            require(octets.all { it in 0..255 }) { "target host '$trimmed' is not a valid IPv4 address" }
        }
        return trimmed
    }
}
