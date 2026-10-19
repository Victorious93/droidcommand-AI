package ai.droidcommand.app.di

import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.app.fleet.AndroidCompanionLink
import ai.droidcommand.app.fleet.BoundCompanionTransport
import ai.droidcommand.app.fleet.FleetNodes
import ai.droidcommand.app.fleet.MessengerFleetTransport
import ai.droidcommand.app.fleet.PackageFleetProbe
import ai.droidcommand.app.fleet.TermuxFleetTransport
import ai.droidcommand.fleet.messenger.PairedNodeGateway
import ai.droidcommand.security.FleetCommandOutcomes
import ai.droidcommand.security.FleetCommandTool
import ai.droidcommand.security.FleetControlPolicy
import ai.droidcommand.security.FleetIntegrationClass
import ai.droidcommand.security.FleetNodeBoard
import ai.droidcommand.security.FleetNodeTransport
import ai.droidcommand.security.FleetOrchestrator
import ai.droidcommand.security.FleetTransportRouter
import ai.droidcommand.termux.TermuxExecutor
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the Fleet Orchestrator. The command tool is registered in the same [ai.droidcommand.agent.ToolRegistry] that
 * backs the [ToolRunner] (a `SecureToolExecutor`), and [FleetOrchestrator] only ever sends an action through that runner,
 * so every action command reaches `ComposeApprovalPrompt` first. The transports are the existing per-class clients
 * ([BoundCompanionTransport] over `core-companion`, [MessengerFleetTransport] over the real, already-tested
 * `core-fleet-messenger` pairing/bridge ([PairedNodeGateway] — the same one `FleetViewModel`'s pairing screen
 * uses), [TermuxFleetTransport] over the existing `TermuxExecutor`); there is no new wire protocol. **2026-10-19:**
 * EXTERNAL_NODE used to get [ai.droidcommand.app.fleet.UnwiredMessengerTransport] here (a transport that always
 * failed, honestly) — this now reaches the real Messenger bridge for every paired EXTERNAL_NODE member
 * (RootForge, File Manager, Keyboard, Mesh, Browser alike), gated the same way every other fleet command is:
 * [FleetOrchestrator]/[FleetCommandTool] still require actual approval, and [MessengerFleetTransport] still
 * reports an unpaired node as "pairing required", never a fabricated reachable state.
 */
@Module
@InstallIn(SingletonComponent::class)
object FleetModule {
    @Provides
    @Singleton
    fun provideFleetControlPolicy(): FleetControlPolicy = FleetControlPolicy(FleetNodes.WIRING)

    @Provides
    @Singleton
    fun provideFleetNodeBoard(control: FleetControlPolicy): FleetNodeBoard = FleetNodeBoard(FleetNodes.nodes(), control)

    @Provides
    @Singleton
    fun provideFleetCommandOutcomes(): FleetCommandOutcomes = FleetCommandOutcomes()

    @Provides
    @Singleton
    fun provideFleetNodeTransport(
        @ApplicationContext context: Context,
        termux: TermuxExecutor,
        messengerGateway: PairedNodeGateway,
    ): FleetNodeTransport {
        val probe = PackageFleetProbe(context)
        return FleetTransportRouter(
            mapOf(
                FleetIntegrationClass.BOUND_COMPANION to BoundCompanionTransport(AndroidCompanionLink(context)),
                FleetIntegrationClass.EXTERNAL_NODE to MessengerFleetTransport(messengerGateway, probe::isInstalled),
                FleetIntegrationClass.TERMUX_IPC to TermuxFleetTransport(termux, probe::isInstalled),
            ),
        )
    }

    @Provides
    @Singleton
    fun provideFleetCommandTool(
        board: FleetNodeBoard,
        control: FleetControlPolicy,
        transport: FleetNodeTransport,
        outcomes: FleetCommandOutcomes,
    ): FleetCommandTool = FleetCommandTool(board, control, transport, outcomes)

    @Provides
    @Singleton
    fun provideFleetOrchestrator(
        board: FleetNodeBoard,
        control: FleetControlPolicy,
        transport: FleetNodeTransport,
        runner: ToolRunner,
        outcomes: FleetCommandOutcomes,
    ): FleetOrchestrator = FleetOrchestrator(board, control, transport, runner, outcomes)
}
