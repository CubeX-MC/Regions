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
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger

/**
 * 竞速与捉迷藏共用的装备托管协议。
 *
 * 这些用例针对一个**真实存在过**的吞装备缺陷：`RoundModeService.restoreStored()`
 * 原本先 `gearStore.take()` 删记录、再写背包。写背包失败（异常、玩家正好掉线、
 * Folia 上实体任务没跑到）时记录已经没了，谁都不知道该还什么。战斗层在 M0 修过
 * 这个顺序，捉迷藏没跟上；现在两者都走 [ModeGearEscrow]。
 *
 * 快照一律用空物品数组，避免在没有 server 的单测里触碰 Paper 的物品序列化。
 */
class ModeGearEscrowTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `restore writes the inventory before the record is deleted`() {
        val harness = harness()
        harness.seed(level = 7)

        assertTrue(harness.escrow().restore(harness.player, "test"))

        verify(harness.inventory).setContents(anyK())
        verify(harness.player).level = 7
        assertNull(harness.persisted(), "写回并确认之后才允许删除 escrow")
    }

    /**
     * 核心回归：写背包失败时记录必须**还在**。
     * 旧的 take-first 顺序在这一步就把玩家的装备弄丢了。
     */
    @Test
    fun `a failed inventory write keeps the record for a later retry`() {
        val harness = harness()
        harness.seed()
        val escrow = harness.escrow()
        // updateInventory 是写背包的最后一步：在这一步注入失败。
        doThrow(RuntimeException("inventory write failed")).`when`(harness.player).updateInventory()

        assertThrows<RuntimeException> { escrow.restore(harness.player, "test") }

        assertNotNull(harness.persisted(), "写回失败必须保留 escrow 记录")

        doNothing().`when`(harness.player).updateInventory()
        assertTrue(escrow.restore(harness.player, "retry"))
        assertNull(harness.persisted(), "重试成功之后才删除")
    }

    /**
     * 宕机可能停在"已经写回、还没删记录"这两步之间。重启后不能拿旧快照覆盖
     * 玩家此后拿到的新背包，只补清理。
     */
    @Test
    fun `an already confirmed record is cleaned up without overwriting the new inventory`() {
        val harness = harness()
        harness.seed()
        // 模拟"写回并确认了，但删记录之前宕机"。
        harness.store().also { it.load() }.markConfirmed(harness.playerId)

        val afterRestart = harness.escrow()
        assertFalse(
            afterRestart.restore(harness.player, "after-restart"),
            "已确认的恢复不该再写一次背包",
        )

        verify(harness.inventory, never()).setContents(anyK())
        assertNull(harness.persisted(), "已确认的记录应当被清理掉")
    }

    @Test
    fun `the confirmation flag survives a reload`() {
        val harness = harness()
        harness.seed()
        harness.store().also { it.load() }.markConfirmed(harness.playerId)

        val reloaded = harness.store().also { it.load() }
        assertTrue(reloaded.peek(harness.playerId)?.confirmed == true, "确认标记必须落盘")
    }

    /** 旧版本写的文件没有 `confirmed` 字段，读出来必须是 false——那是安全的一侧。 */
    @Test
    fun `records written before the confirmation flag existed default to unconfirmed`() {
        val harness = harness()
        harness.seed()
        val file = tempDir.resolve(ESCROW_FILE).toFile()
        file.writeText(file.readText().replace("confirmed: false", "").replace("confirmed: true", ""))

        val reloaded = harness.store().also { it.load() }
        assertEquals(false, reloaded.peek(harness.playerId)?.confirmed)
    }

    @Test
    fun `restore does nothing when the player has no escrow`() {
        val harness = harness()
        assertFalse(harness.escrow().restore(harness.player, "test"))
        verify(harness.inventory, never()).setContents(anyK())
    }

    @Test
    fun `capture does not overwrite an existing snapshot`() {
        val harness = harness()
        harness.seed(level = 3, regionId = "arena")

        // 同一场比赛只捕获一次原始装备：已有记录时 capture 直接返回 null，不碰 store。
        assertNull(harness.escrow().capture(harness.player, "other", null))

        val stored = harness.persisted()
        assertNotNull(stored)
        assertEquals(3, stored!!.level, "重复捕获覆盖了入场前快照")
        assertEquals("arena", stored.regionId)
    }

    @Test
    fun `escrowed players are reported per region`() {
        val harness = harness()
        val other = UUID.randomUUID()
        harness.seed(regionId = "arena")
        harness.store().also { it.load() }.put(other, "elsewhere", snapshot())

        val escrow = harness.escrow()
        assertTrue(escrow.isEscrowed(harness.playerId))
        assertTrue(escrow.pendingPlayerIds().containsAll(setOf(harness.playerId, other)))
        assertEquals("arena", escrow.escrowedRegion(harness.playerId))
        assertEquals("elsewhere", escrow.escrowedRegion(other))
    }

    // ------------------------------------------------------------ harness

    private fun harness(): Harness {
        val plugin = mock(RegionsPlugin::class.java)
        `when`(plugin.dataFolder).thenReturn(tempDir.toFile())
        `when`(plugin.log()).thenReturn(CubexLogger(Logger.getLogger("ModeGearEscrowTest")))
        val player = mock(Player::class.java)
        val inventory = mock(PlayerInventory::class.java)
        `when`(player.inventory).thenReturn(inventory)
        `when`(player.name).thenReturn("Runner")
        val playerId = UUID.randomUUID()
        `when`(player.uniqueId).thenReturn(playerId)
        return Harness(plugin, player, inventory, playerId)
    }

    private inner class Harness(
        val plugin: RegionsPlugin,
        val player: Player,
        val inventory: PlayerInventory,
        val playerId: UUID,
    ) {
        fun store(): CombatGearStore = CombatGearStore(plugin, ESCROW_FILE)

        /** 每次都重新读盘，模拟"服务重新构造"（重启／reload）。 */
        fun escrow(): ModeGearEscrow = ModeGearEscrow(plugin, ESCROW_FILE, "test").also { it.load() }

        fun seed(level: Int = 1, regionId: String = "arena") {
            store().put(playerId, regionId, snapshot(level))
        }

        fun persisted(): CombatGearStore.StoredGear? = store().also { it.load() }.peek(playerId)

    }

    private fun snapshot(level: Int = 1) = GearSnapshot(
        emptyArray<ItemStack?>(),
        emptyArray<ItemStack?>(),
        null,
        level,
        0.0f,
        GameMode.SURVIVAL,
        null,
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyK(): T {
        org.mockito.Mockito.any<T>()
        return null as T
    }

    private companion object {
        const val ESCROW_FILE = "test-escrow.yml"
    }
}
