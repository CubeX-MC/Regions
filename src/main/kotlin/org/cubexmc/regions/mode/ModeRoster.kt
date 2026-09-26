package org.cubexmc.regions.mode

import org.cubexmc.regions.match.MatchPhase
import org.cubexmc.regions.match.ParticipantState
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 竞速与捉迷藏的**显式**报名册。
 *
 * 在这之前这两类玩法是"走进区域即入队"：`onEnter` 直接把人加进名单，于是路过的人
 * 会被卷进一场比赛，装备可能被换掉，而他从没说过要参加。战斗层在 M3 已经改成
 * "只有 join 才写入名单"，这个类把同一条规则给另外四种玩法。
 *
 * 阶段沿用 [MatchPhase]（竞速/捉迷藏不使用 `COUNTDOWN` 与 `INTERMISSION`），
 * 这样 `labels.state.*` 一套文案覆盖全部八种玩法，玩家不会在不同玩法里看到
 * 同一个状态的两种说法。
 */
class ModeRoster(val regionId: String) {

    private val participants: MutableMap<UUID, ParticipantState> = ConcurrentHashMap()
    private val spectators: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val eliminationOrder: MutableList<UUID> = Collections.synchronizedList(ArrayList())

    @Volatile
    var phase: MatchPhase = MatchPhase.WAITING
        private set

    @Volatile
    var startedAtMillis: Long = 0L
        private set

    /** 本场的稳定 id：结果、日志与审计都引用它，重开一局就换一个。 */
    val matchId: UUID = UUID.randomUUID()

    // ------------------------------------------------------------ 查询

    fun isParticipant(playerId: UUID): Boolean = participants.containsKey(playerId)

    fun isSpectator(playerId: UUID): Boolean = spectators.contains(playerId)

    fun stateOf(playerId: UUID): ParticipantState? = participants[playerId]

    fun participantIds(): Set<UUID> = participants.keys.toSet()

    fun spectatorIds(): Set<UUID> = spectators.toSet()

    /** 仍在场上的人：未淘汰、未退赛。 */
    fun aliveIds(): Set<UUID> =
        participants.entries.filter { it.value == ParticipantState.ALIVE }.mapTo(LinkedHashSet()) { it.key }

    fun readyIds(): Set<UUID> =
        participants.entries.filter { it.value == ParticipantState.READY }.mapTo(LinkedHashSet()) { it.key }

    fun size(): Int = participants.size

    fun readyCount(): Int = readyIds().size

    fun isEmpty(): Boolean = participants.isEmpty() && spectators.isEmpty()

    fun eliminationOrderSnapshot(): List<UUID> = synchronized(eliminationOrder) { eliminationOrder.toList() }

    // ------------------------------------------------------------ 报名

    /** 报名。已在名单里返回 false，让调用方给出"你已经报名了"而不是静默成功。 */
    fun join(playerId: UUID): Boolean {
        spectators.remove(playerId)
        return participants.putIfAbsent(playerId, ParticipantState.WAITING) == null
    }

    fun ready(playerId: UUID): Boolean {
        val current = participants[playerId] ?: return false
        if (current != ParticipantState.WAITING) return false
        participants[playerId] = ParticipantState.READY
        return true
    }

    fun unready(playerId: UUID): Boolean {
        val current = participants[playerId] ?: return false
        if (current != ParticipantState.READY) return false
        participants[playerId] = ParticipantState.WAITING
        return true
    }

    /** 退出名单。返回此前的状态，null 表示他本来就不在名单里。 */
    fun remove(playerId: UUID): ParticipantState? {
        val previous = participants.remove(playerId)
        spectators.remove(playerId)
        return previous
    }

    fun spectate(playerId: UUID): Boolean {
        participants.remove(playerId)
        return spectators.add(playerId)
    }

    fun removeSpectator(playerId: UUID): Boolean = spectators.remove(playerId)

    // ------------------------------------------------------------ 进行中

    /** 开赛：把名单里的人全部标记为存活，并记录开赛时刻。 */
    fun begin(nowMillis: Long) {
        for (playerId in participants.keys.toList()) {
            participants[playerId] = ParticipantState.ALIVE
        }
        eliminationOrder.clear()
        startedAtMillis = nowMillis
        phase = MatchPhase.RUNNING
    }

    fun movePhase(next: MatchPhase) {
        phase = next
    }

    /**
     * 淘汰一名选手并记录顺序。重复淘汰只记一次——死亡、退出与断线回调可能同时到达，
     * 计两次会把名次算错。
     */
    fun eliminate(playerId: UUID, state: ParticipantState = ParticipantState.ELIMINATED): Boolean {
        val current = participants[playerId] ?: return false
        if (current != ParticipantState.ALIVE && current != ParticipantState.WAITING && current != ParticipantState.READY) {
            return false
        }
        participants[playerId] = state
        synchronized(eliminationOrder) {
            if (!eliminationOrder.contains(playerId)) eliminationOrder.add(playerId)
        }
        return true
    }

    fun markRestoring(playerId: UUID) {
        if (participants.containsKey(playerId)) participants[playerId] = ParticipantState.RESTORING
    }

    fun markRestored(playerId: UUID) {
        if (participants.containsKey(playerId)) participants[playerId] = ParticipantState.RESTORED
    }

    fun clear() {
        participants.clear()
        spectators.clear()
        synchronized(eliminationOrder) { eliminationOrder.clear() }
    }
}
