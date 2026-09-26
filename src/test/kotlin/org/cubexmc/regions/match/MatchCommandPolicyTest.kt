package org.cubexmc.regions.match

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 比赛期间的指令封锁（2026-09-19 实机反馈：「工会战不能允许 /l edit 吧，或者 PVP 不允许指令」）。
 */
class MatchCommandPolicyTest {

    private val allowed = listOf("msg", "r")

    @Test
    fun `escape hatches are blocked by default`() {
        for (command in listOf("spawn", "home", "tp", "back", "l", "lands", "warp")) {
            assertTrue(MatchCommandPolicy.isBlocked(command, allowed), command)
        }
    }

    @Test
    fun `the plugin's own commands always work so nobody is trapped`() {
        for (command in listOf("regions", "region", "venue")) {
            assertFalse(MatchCommandPolicy.isBlocked(command, emptyList()), command)
        }
    }

    @Test
    fun `the owner can allow extra commands`() {
        assertFalse(MatchCommandPolicy.isBlocked("msg", allowed))
        assertFalse(MatchCommandPolicy.isBlocked("R", allowed), "大小写不敏感")
    }

    @Test
    fun `a namespaced command cannot sneak past the block`() {
        assertTrue(MatchCommandPolicy.isBlocked("lands:l", allowed), "/lands:l edit 必须和 /l 同样被挡")
        assertTrue(MatchCommandPolicy.isBlocked("essentials:home", allowed))
        assertFalse(MatchCommandPolicy.isBlocked("regions:regions", allowed))
    }

    @Test
    fun `an empty input is not a command`() {
        assertFalse(MatchCommandPolicy.isBlocked("", allowed))
        assertFalse(MatchCommandPolicy.isBlocked("   ", allowed))
    }
}
