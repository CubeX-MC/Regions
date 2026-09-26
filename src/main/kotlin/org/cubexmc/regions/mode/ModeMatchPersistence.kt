package org.cubexmc.regions.mode

import org.cubexmc.regions.match.MatchOutcome
import org.cubexmc.regions.match.MatchParticipant
import org.cubexmc.regions.match.MatchPhase
import org.cubexmc.regions.match.MatchResult
import org.cubexmc.regions.match.MatchSnapshot
import org.cubexmc.regions.match.MatchStore
import org.cubexmc.regions.match.RewardState
import org.cubexmc.regions.model.RegionDefinition
import java.util.UUID

/** Durable match identity for race and hide-and-seek, independent of their gear escrow. */
internal object ModeMatchPersistence {
    fun begin(store: MatchStore, region: RegionDefinition, roster: ModeRoster, names: (UUID) -> String): Boolean =
        store.persist(
            MatchSnapshot(
                matchId = roster.matchId,
                regionId = region.id,
                publishedRevision = region.publishedRevision ?: region.revision,
                modeType = region.mode?.type.orEmpty(),
                createdAtMillis = System.currentTimeMillis(),
                options = emptyMap(),
                participants = roster.participantIds().map { MatchParticipant(it, names(it), null, roster.stateOf(it)!!) },
                teams = emptyMap(),
                phase = MatchPhase.PREPARING,
                round = 1,
                result = null,
                pendingRestore = emptySet(),
                confirmedRestore = emptySet(),
            ),
        )

    fun finish(store: MatchStore, result: MatchResult): Boolean {
        store.putResult(result)
        store.remove(result.matchId)
        return store.save()
    }

    /** Never resume a partially played match. Escrow recovery is handled on each player's entity thread. */
    fun recover(store: MatchStore, accepts: (String) -> Boolean, reason: String): Int {
        var count = 0
        val snapshots = store.all().filter { accepts(it.modeType) }
        if (snapshots.isEmpty()) return 0
        for (snapshot in snapshots) {
            if (snapshot.phase != MatchPhase.CLOSED) {
                store.putResult(
                    MatchResult(
                        resultId = UUID.randomUUID(),
                        matchId = snapshot.matchId,
                        regionId = snapshot.regionId,
                        modeType = snapshot.modeType,
                        outcome = MatchOutcome.ABORTED,
                        winnerIds = emptySet(),
                        winnerTeamId = null,
                        reasonKey = "game.match.reason.server-stop",
                        rewardState = RewardState.NONE,
                        finishedAtMillis = System.currentTimeMillis(),
                        standings = snapshot.participants.map { it.playerId },
                    ),
                )
                count++
            }
            store.remove(snapshot.matchId)
        }
        check(store.save()) { "Could not persist $reason recovery for interrupted mode matches" }
        return count
    }
}
