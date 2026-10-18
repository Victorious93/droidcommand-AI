package ai.droidcommand.app.di

import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.app.fleet.AndroidCompanionLink
import ai.droidcommand.app.fleet.BoundCompanionTransport
import ai.droidcommand.app.fleet.ExternalNodeTransport
import ai.droidcommand.app.fleet.FleetNodes
import ai.droidcommand.app.fleet.PackageFleetProbe
import ai.droidcommand.app.fleet.TermuxFleetTransport
import ai.droidcommand.app.fleet.UnwiredMessengerTransport
import ai.droidcommand.rootforge.RootForgeClient
import ai.droidcommand.rootforge.RootForgeNodeRegistry
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
 * ([BoundCompanionTransport] over `core-companion`, [ExternalNodeTransport] over `core-rootforge`,
 * [TermuxFleetTransport] over the existing `TermuxExecutor`); there is no new wire protocol.
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
    fun provideFleetNodeTransport(@ApplicationContext context: Context, termux: TermuxExecutor): FleetNodeTransport {
        val probe = PackageFleetProbe(context)
        val rootForge = RootForgeNodeRegistry(emptyList()) { RootForgeClient(it, UnwiredMessengerTransport()) }
        return FleetTransportRouter(
            mapOf(
                FleetIntegrationClass.BOUND_COMPANION to BoundCompanionTransport(AndroidCompanionLink(context)),
                FleetIntegrationClass.EXTERNAL_NODE to ExternalNodeTransport(rootForge, probe::isInstalled),
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
