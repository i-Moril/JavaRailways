package work.art1st.mineRails.bukkit.logic

import org.bukkit.World as BukkitWorld
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.debug.AtoStallDiagnostics
import work.art1st.mineRails.bukkit.extension.BukkitWorldData
import work.art1st.mineRails.bukkit.render.chunk.RenderChunkManager
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.di.KoinWorldParentContext
import work.art1st.mineRails.core.di.mineRailsParentScope
import work.art1st.mineRails.basic.BasicPlugin
import work.art1st.mineRails.basic.api.ato.AtoSafetyProfile
import work.art1st.mineRails.basic.api.ato.AtoWorldCoordinator
import work.art1st.mineRails.basic.api.ato.KinematicTrainActuator
import work.art1st.mineRails.basic.BasicWorldData
import work.art1st.mineRails.basic.controller.SimpleATO
import work.art1st.mineRails.basic.safety.RailSpeedCalculator
import work.art1st.mineRails.basic.safety.SpeedSafetyMonitor
import work.art1st.mineRails.basic.timetable.TimetableAtoFactory
import work.art1st.mineRails.basic.timetable.TimetableDispatcher
import work.art1st.mineRails.bukkit.extension.train.rendererOrNull
import work.art1st.mineRails.bukkit.persistence.RailRenderMetaStore
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.api.util.annotation.Heavy
import work.art1st.mineRails.core.util.ThreadGuard
import work.art1st.mineRails.core.util.TickManagerImpl
import work.art1st.mineRails.core.physics.tick.PhysicsCoordinator
import java.util.concurrent.Executors

/**
 * 为 Bukkit 世界创建对应的 Core [World]：Tick、Koin 父 scope、[BukkitWorldData] 扩展一步完成。
 */
object RailWorldFactory {
    @OptIn(Heavy::class)
    fun createForBukkit(
        bukkitWorld: BukkitWorld,
        snapshot: CoreWorld.SavedWorldV1? = null
    ): World {
        ThreadGuard.ensureMainThread("RailWorldFactory.createForBukkit")
        val tickManager = TickManagerImpl(
            Executors.newSingleThreadScheduledExecutor(),
            System.currentTimeMillis()
        )
        val physicsCoordinator = PhysicsCoordinator(
            workerThreads = BukkitPlugin.INSTANCE.mineRailsConfig.physicsWorkerThreads
        )
        val worldParent = KoinWorldParentContext(mineRailsParentScope())
        val coreWorld = if (snapshot != null) {
            snapshot.load(CoreWorld.SavedWorldV1.Dependency(tickManager, worldParent, physicsCoordinator))
        } else {
            CoreWorld.createEmpty(tickManager, worldParent, physicsCoordinator)
        }
        val renderChunkManager = RenderChunkManager(bukkitWorld)
        coreWorld.registerExtensionData(
            BukkitPlugin.INSTANCE,
            BukkitWorldData(renderChunkManager, bukkitWorld)
        )
        val plugin = BukkitPlugin.INSTANCE
        val atoLogger = plugin.logger
        val pluginDataFolder = plugin.dataFolder
        coreWorld.registerExtensionData(
            BasicPlugin.INSTANCE,
            BasicWorldData(
                dispatcher = TimetableDispatcher(TimetableAtoFactory { train, world ->
                    val profile = AtoSafetyProfile()
                    val packageName = train.rendererOrNull?.modelPackageName()
                    val maximumSpeed = plugin.modelManager.getTrainControl(packageName).maxSpeed
                    SimpleATO(
                        train,
                        world,
                        KinematicTrainActuator(maximumSpeed, profile),
                        profile
                    )
                }),
                atoCoordinator = AtoWorldCoordinator(bukkitWorld.uid.toString()) { report ->
                    atoLogger.severe(report.format())
                    runCatching { AtoStallDiagnostics.write(pluginDataFolder, report) }
                        .onSuccess { file ->
                            atoLogger.warning("ATO Stall diagnostics written to ${file.absolutePath}")
                        }
                        .onFailure { error ->
                            atoLogger.severe(
                                "Failed to write ATO Stall diagnostics: " +
                                    "${error::class.simpleName}: ${error.message.orEmpty()}"
                            )
                        }
                }
            )
        )
        val safetyConfig = BukkitPlugin.INSTANCE.mineRailsConfig
        coreWorld.allRails.forEach { rail ->
            val requestedSpeed = rail.metadata.designSpeedMetersPerSecond ?: return@forEach
            val limits = RailSpeedCalculator.calculate(
                rail.curve,
                requestedSpeed,
                rail.metadata.geometry.allowableCantDeficiencyMeters,
                safetyConfig.derailLateralAccelerationMetersPerSecondSquared
            )
            rail.metadata = rail.metadata.copy(
                speedLimitMetersPerSecond = limits.operatingLimitMetersPerSecond,
                derailSpeedMetersPerSecond = limits.derailSpeedMetersPerSecond
            )
        }
        tickManager.register(
            SpeedSafetyMonitor(
                coreWorld,
                SpeedSafetyMonitor.Config(
                    derailEnabled = safetyConfig.overspeedDerailEnabled,
                    toleranceMetersPerSecond = safetyConfig.overspeedToleranceKmH / 3.6,
                    graceTicks = safetyConfig.overspeedGraceTicks
                )
            )
        )
        val persisted = RailRenderMetaStore.load(bukkitWorld)
        val restored = persisted.filter { RailVisualBootstrap.fromPersisted(coreWorld, it) }
        if (restored.size != persisted.size) {
            RailRenderMetaStore.saveAll(bukkitWorld, restored)
        }
        physicsCoordinator.start()
        return coreWorld
    }
}
