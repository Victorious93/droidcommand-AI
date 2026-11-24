package ai.droidcommand.app.fleetworker

import ai.droidcommand.fleet.worker.WorkerPersisted

/** When the phone should be talking to the coordinator. One rule, in one place, used by the service and the screen. */
object FleetWorkerPolicy {
    /** The owner switched the worker on **and** this phone is paired **and** the coordinator has not revoked it. */
    fun shouldRun(workerEnabled: Boolean, state: WorkerPersisted): Boolean =
        workerEnabled && state.deviceId != null && state.credential != null && !state.revoked
}
