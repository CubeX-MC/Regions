package org.cubexmc.regions.mode

import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.cubexmc.core.CubexLogger
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.match.GearSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger

/**
 * 装备托管与恢复顺序（[Regions/PLAN.md](../../../PLAN.md) M0.3）：
 * peek 读取不删除、take 确认删除且落盘失败回滚、restoreStored 先写背包后删记录、
 * 写背包失败保留 escrow 等待重试、旧记录可读回。
 *
 * 测试里的快照全部使用空物品数组，避免在没有 server 的单测里触碰 Paper 的物品序列化。
 */
class CombatGearRestoreTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `peek returns the stored gear without deleting it`() {
        val store = store()
        val id = UUID.randomUUID()
        store.put(id, "arena", snapshot())

        assertNotNull(store.peek(id))
        assertNotNull(store.peek(id), "peek must not consume the record")
        assertEquals("arena", store.peek(id)?.regionId)

        store.take(id)
        assertNull(store.peek(id))
    }

    @Test
    fun `take rolls back when persisting the removal fails`() {
        val store = store()
        val id = UUID.randomUUID()
        store.put(id, "arena", snapshot())
        breakSaveTarget()

        assertThrows<IOException> { store.take(id) }
        assertNotNull(store.peek(id), "a failed confirm-delete must keep the escrow record")

        healSaveTarget()
        store.take(id)
        assertNull(store.peek(id))
    }

    @Test
    fun `a fresh store reads back the persisted record`() {
        val id = UUID.randomUUID()
        store().put(id, "arena", snapshot(level = 3, exp = 0.5f))

        val reloaded = store()
        reloaded.load()
        val stored = reloaded.peek(id)

        assertNotNull(stored, "an old persisted escrow record must still be readable")
        assertEquals("arena", stored?.regionId)
        assertEquals(3, stored?.level)
        assertEquals(0.5f, stored?.exp)
        assertEquals(GameMode.SURVIVAL, stored?.gameMode)
    }

    @Test
    fun `restoreIfPending with no escrow touches nothing`() {
        val harness = serviceHarness()
        val service = CombatModeService(harness.plugin)

        assertFalse(service.restoreIfPending(harness.player, "test"))
        verifyNoInteractions(harness.inventory)
    }

    @Test
    fun `a failed inventory write keeps the escrow record for a later retry`() {
        val harness = serviceHarness()
        val id = harness.playerId
        store().put(id, "arena", snapshot())
        val service = CombatModeService(harness.plugin)
        // updateInventory 是写背包的最后一步：在这一步注入失败，
        // 新顺序下记录必须还在（旧顺序的 take-first 会先把记录删掉）。
        doThrow(RuntimeException("inventory write failed")).`when`(harness.player).updateInventory()

        assertThrows<RuntimeException> { service.restoreIfPending(harness.player, "test") }

        assertNotNull(freshLoadedRecord(id), "restore write failure must preserve the escrow record")

        doNothing().`when`(harness.player).updateInventory()
        assertTrue(service.restoreIfPending(harness.player, "test-retry"))

        assertNull(freshLoadedRecord(id), "escrow is only deleted after the restore was written and confirmed")
        // 第一次恢复（失败）和重试（成功）各写一次背包。
        verify(harness.inventory, org.mockito.Mockito.times(2)).setContents(anyK())
    }

    @Test
    fun `successful restore writes the stored snapshot before deleting the record`() {
        val harness = serviceHarness()
        val id = harness.playerId
        store().put(id, "arena", snapshot(level = 7))
        // service 在 put 之后构造，这样它启动时才会从磁盘把这条 escrow 读进内存。
        val service = CombatModeService(harness.plugin)

        assertTrue(service.restoreIfPending(harness.player, "test"))

        verify(harness.inventory).setContents(anyK())
        verify(harness.player).level = 7
        assertNull(freshLoadedRecord(id))
    }

    private class Harness(
        val plugin: RegionsPlugin,
        val player: Player,
        val inventory: PlayerInventory,
        val playerId: UUID,
    )

    private fun serviceHarness(): Harness {
        val plugin = mock(RegionsPlugin::class.java)
        `when`(plugin.dataFolder).thenReturn(tempDir.toFile())
        `when`(plugin.log()).thenReturn(CubexLogger(Logger.getLogger("CombatGearRestoreTest")))
        val player = mock(Player::class.java)
        val inventory = mock(PlayerInventory::class.java)
        `when`(player.inventory).thenReturn(inventory)
        `when`(player.name).thenReturn("Fighter")
        val playerId = UUID.randomUUID()
        `when`(player.uniqueId).thenReturn(playerId)
        return Harness(plugin, player, inventory, playerId)
    }

    private fun store(): CombatGearStore {
        val plugin = mock(RegionsPlugin::class.java)
        `when`(plugin.dataFolder).thenReturn(tempDir.toFile())
        return CombatGearStore(plugin)
    }

    private fun freshLoadedRecord(playerId: UUID): CombatGearStore.StoredGear? {
        val reloaded = store()
        reloaded.load()
        return reloaded.peek(playerId)
    }

    private fun snapshot(level: Int = 1, exp: Float = 0.0f) = GearSnapshot(
        arrayOfNulls(0),
        arrayOfNulls(0),
        null,
        level,
        exp,
        GameMode.SURVIVAL,
        null,
    )

    private fun <T> anyK(): T = org.mockito.ArgumentMatchers.any()

    /** 把 escrow 文件变成目录，让任何落盘尝试都失败。 */
    private fun breakSaveTarget() {
        val file = File(tempDir.toFile(), "combat-escrow.yml")
        assertTrue(file.delete())
        assertTrue(file.mkdirs())
    }

    private fun healSaveTarget() {
        val file = File(tempDir.toFile(), "combat-escrow.yml")
        assertTrue(file.isDirectory)
        assertTrue(file.delete())
    }
}
