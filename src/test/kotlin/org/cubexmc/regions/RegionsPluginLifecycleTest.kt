package org.cubexmc.regions

import org.cubexmc.core.CubexLogger
import org.cubexmc.regions.mode.CombatModeService
import org.cubexmc.regions.mode.RaceModeService
import org.cubexmc.regions.mode.RoundModeService
import org.cubexmc.regions.service.RegionSessionService
import org.cubexmc.regions.service.RegionTrialService
import org.cubexmc.regions.storage.RegionStorage
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doCallRealMethod
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.logging.Logger

/**
 * 半初始化关闭（[Regions/PLAN.md](../../../../PLAN.md) M0.2）：enablePlugin 在任意一步抛错后，
 * Bukkit 仍会调用 onDisable。disablePlugin 必须按实际初始化状态清理——服务未构造不报
 * "not initialized"，单步失败不吞掉其余清理步骤。
 *
 * RegionsPlugin 无法在单测里构造（JavaPlugin 构造器要求 PluginClassLoader），
 * 所以这里用 mock 实例调用真实的 [RegionsPlugin.runShutdownCleanup]，
 * 字段全部保持未初始化或用反射注入 mock 服务。
 */
class RegionsPluginLifecycleTest {

    @Test
    fun `shutdown with nothing initialized does not throw`() {
        val plugin = shutdownPlugin()

        assertDoesNotThrow { plugin.runShutdownCleanup() }
    }

    @Test
    fun `shutdown cleans up every bound mode service with shuttingDown`() {
        val plugin = shutdownPlugin()
        val combat = mock(CombatModeService::class.java)
        val round = mock(RoundModeService::class.java)
        val race = mock(RaceModeService::class.java)
        val trial = mock(RegionTrialService::class.java)
        val session = mock(RegionSessionService::class.java)
        setField(plugin, "combatModeService", combat)
        setField(plugin, "roundModeService", round)
        setField(plugin, "raceModeService", race)
        setField(plugin, "trialService", trial)
        setField(plugin, "sessionService", session)

        plugin.runShutdownCleanup()

        verify(combat).cleanupAll("plugin-disable", true)
        verify(round).cleanupAll("plugin-disable", true)
        verify(race).cleanupAll("plugin-disable", true)
        verify(trial).cleanupAll("plugin-disable", true)
        verify(session).cleanupAll("plugin-disable", true)
    }

    @Test
    fun `a failing cleanup step does not block the remaining shutdown steps`() {
        val plugin = shutdownPlugin()
        val combat = mock(CombatModeService::class.java)
        doThrow(IllegalStateException("scheduler gone"))
            .`when`(combat)
            .cleanupAll("plugin-disable", true)
        val round = mock(RoundModeService::class.java)
        val storage = mock(RegionStorage::class.java)
        `when`(storage.flushIfDirty()).thenReturn(true)
        setField(plugin, "combatModeService", combat)
        setField(plugin, "roundModeService", round)
        setField(plugin, "regionStorage", storage)

        assertDoesNotThrow { plugin.runShutdownCleanup() }

        verify(combat).cleanupAll("plugin-disable", true)
        verify(round).cleanupAll("plugin-disable", true)
        verify(storage).flushIfDirty()
    }

    private fun shutdownPlugin(): RegionsPlugin {
        val plugin = mock(RegionsPlugin::class.java)
        doCallRealMethod().`when`(plugin).runShutdownCleanup()
        `when`(plugin.log()).thenReturn(CubexLogger(Logger.getLogger("RegionsPluginLifecycleTest")))
        return plugin
    }

    private fun setField(plugin: RegionsPlugin, name: String, value: Any?) {
        RegionsPlugin::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(plugin, value)
        }
    }
}
