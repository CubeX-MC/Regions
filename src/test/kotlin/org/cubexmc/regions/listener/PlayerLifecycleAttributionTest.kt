package org.cubexmc.regions.listener

import org.bukkit.Server
import org.bukkit.entity.AreaEffectCloud
import org.bukkit.entity.Arrow
import org.bukkit.entity.Player
import org.bukkit.entity.TNTPrimed
import org.bukkit.entity.Wolf
import org.bukkit.entity.Zombie
import org.cubexmc.regions.RegionsPlugin
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID

/**
 * 伤害归因（PLAN.md §6.3.2）。
 *
 * 重点是滞留药水：`AreaEffectCloud.getSource()` 给的是 `ProjectileSource`（投掷者本人），
 * 而 `Projectile` 并不实现 `ProjectileSource`，所以"先当投射物再取 shooter"这条路永远走不通。
 * 归因失败的代价是双向的——比赛里这次伤害被当成来源不明直接拒绝，比赛外则因为没有责任人
 * 而跳过场地 `pvp` 规则。
 */
class PlayerLifecycleAttributionTest {

    private val plugin: RegionsPlugin = mock(RegionsPlugin::class.java)
    private val listener = PlayerLifecycleListener(plugin)

    @Test
    fun `melee damage is attributed to the attacker`() {
        val attacker = mock(Player::class.java)
        assertSame(attacker, listener.attackerOf(attacker))
    }

    @Test
    fun `projectile damage is attributed to its shooter`() {
        val shooter = mock(Player::class.java)
        val arrow = mock(Arrow::class.java)
        `when`(arrow.shooter).thenReturn(shooter)
        assertSame(shooter, listener.attackerOf(arrow))
    }

    @Test
    fun `lingering potion damage is attributed to the thrower`() {
        val thrower = mock(Player::class.java)
        val cloud = mock(AreaEffectCloud::class.java)
        `when`(cloud.source).thenReturn(thrower)
        assertSame(thrower, listener.attackerOf(cloud), "the cloud's source is the thrower itself")
    }

    @Test
    fun `lingering potion from a dispenser has no responsible player`() {
        val cloud = mock(AreaEffectCloud::class.java)
        `when`(cloud.source).thenReturn(null)
        assertNull(listener.attackerOf(cloud))
    }

    @Test
    fun `an offline pet owner leaves the damage unattributed`() {
        val wolf = mock(Wolf::class.java)
        val ownerId = UUID.randomUUID()
        val server = mock(Server::class.java)
        `when`(wolf.ownerUniqueId).thenReturn(ownerId)
        `when`(plugin.server).thenReturn(server)
        `when`(server.getPlayer(ownerId)).thenReturn(null)
        assertNull(listener.attackerOf(wolf), "an unattributable player source must stay unattributed")
    }

    @Test
    fun `primed tnt keeps the player who lit it`() {
        val lighter = mock(Player::class.java)
        val tnt = mock(TNTPrimed::class.java)
        `when`(tnt.source).thenReturn(lighter)
        assertSame(lighter, listener.attackerOf(tnt))
    }

    @Test
    fun `mob damage is not player sourced`() {
        assertNull(listener.attackerOf(mock(Zombie::class.java)))
    }
}
