package ai.droidcommand.remotecontrol

/**
 * Compatibility alias for the frozen [FleetRemoteEnvelope].
 * VictorSMS inbound uses op [FleetRemoteEnvelope.OP_REMOTE_COMMAND], not a
 * separate SMS op name.
 */
typealias RemoteCommandEnvelope = FleetRemoteEnvelope
