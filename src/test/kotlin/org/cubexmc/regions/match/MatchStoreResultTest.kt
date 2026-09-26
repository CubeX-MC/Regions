package org.cubexmc.regions.match

import org.cubexmc.core.CubexLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger

/**
 * 比赛结果要活得比比赛记录久（2026-09-19 实机反馈）。
 *
 * `close()` 会把整条比赛从 `matches.yml` 删掉（否则文件无限增长），
 * 但重启后 `/regions game <id> result` 不该因此变成空白。每个场地只留最后一条，
 * 容量跟场地数走。
 */
class MatchStoreResultTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `the last result of a venue survives a reload`() {
        val file = File(tempDir.toFile(), "matches.yml")
        val store = MatchStore(file, CubexLogger(Logger.getLogger("MatchStoreResultTest")))
        store.reload()

        val result = result("arena", MatchOutcome.NATURAL)
        store.putResult(result)
        store.save()

        val reopened = MatchStore(file, CubexLogger(Logger.getLogger("MatchStoreResultTest")))
        reopened.reload()

        val loaded = reopened.lastResult("arena")
        assertNotNull(loaded)
        assertEquals(result.resultId, loaded!!.resultId)
        assertEquals(MatchOutcome.NATURAL, loaded.outcome)
        assertEquals(result.winnerIds, loaded.winnerIds)
        assertEquals(RewardState.SETTLED, loaded.rewardState)
        assertEquals("game.match.reason.last-standing", loaded.reasonKey)
        assertNull(reopened.lastResult("other"), "别的场地没有记录")
    }

    @Test
    fun `a newer result replaces the previous one instead of piling up`() {
        val file = File(tempDir.toFile(), "matches.yml")
        val store = MatchStore(file, CubexLogger(Logger.getLogger("MatchStoreResultTest")))
        store.reload()

        store.putResult(result("arena", MatchOutcome.NATURAL))
        val second = result("arena", MatchOutcome.DRAW)
        store.putResult(second)
        store.save()

        val reopened = MatchStore(file, CubexLogger(Logger.getLogger("MatchStoreResultTest")))
        reopened.reload()
        assertEquals(second.resultId, reopened.lastResult("arena")?.resultId)
        assertEquals(MatchOutcome.DRAW, reopened.lastResult("arena")?.outcome)
    }

    private fun result(regionId: String, outcome: MatchOutcome) = MatchResult(
        resultId = UUID.randomUUID(),
        matchId = UUID.randomUUID(),
        regionId = regionId,
        modeType = "free_for_all",
        outcome = outcome,
        winnerIds = setOf(UUID.randomUUID()),
        winnerTeamId = null,
        reasonKey = "game.match.reason.last-standing",
        reasonArgs = mapOf("name" to "Steve"),
        forcedBy = null,
        rewardState = RewardState.SETTLED,
        finishedAtMillis = 1_726_000_000_000L,
        eliminationOrder = listOf(UUID.randomUUID()),
    )
}
