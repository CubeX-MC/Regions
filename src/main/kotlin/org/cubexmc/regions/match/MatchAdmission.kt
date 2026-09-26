package org.cubexmc.regions.match

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One player may belong to only one match, including spectator and preparation slots. */
class MatchAdmission {
    private val entries = ConcurrentHashMap<UUID, UUID>()

    fun reserve(playerId: UUID, matchId: UUID): String? {
        val previous = entries.putIfAbsent(playerId, matchId)
        return if (previous == null || previous == matchId) null else "game.match.join.other-match"
    }

    fun release(playerId: UUID, matchId: UUID) {
        entries.remove(playerId, matchId)
    }

    fun releaseMatch(matchId: UUID) {
        entries.entries.removeIf { it.value == matchId }
    }
}
