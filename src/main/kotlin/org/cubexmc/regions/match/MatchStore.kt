package org.cubexmc.regions.match

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.core.CubexLogger
import org.cubexmc.core.Reloadable
import org.cubexmc.core.Terminable
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * 比赛恢复元数据的落盘单元（PLAN.md §6.1 的 `MatchStore`，schema 1）。
 *
 * 三类“可变真相”分开存放，任何一个文件都不会各自保存一份副本：
 * 比赛阶段/选手/结果/恢复进度在这里，装备内容只在
 * [org.cubexmc.regions.mode.CombatGearStore]，资金 lease 只在
 * [org.cubexmc.regions.storage.RewardFundingStore]。
 *
 * 只在关键转移落盘（到达开赛屏障、锁定 roster、产生终态、恢复进度变化），
 * 不每 tick 保存整个文件。
 */
class MatchStore(
    private val file: File,
    private val logger: CubexLogger,
) : Reloadable, Terminable {
    private val matches: MutableMap<UUID, MatchSnapshot> = LinkedHashMap()

    /**
     * 每个场地**最后一次**比赛结果。比赛收尾时会把整条记录删掉（否则文件无限增长），
     * 结果却要活得更久一点：重启后 `/regions game <id> result` 不该变成一片空白。
     * 按场地各留一条，所以容量跟场地数走，不会膨胀。
     */
    private val lastResults: MutableMap<String, MatchResult> = LinkedHashMap()
    private var dirty = false

    val isDirty: Boolean
        @Synchronized get() = dirty

    @Synchronized
    fun all(): List<MatchSnapshot> = matches.values.toList()

    @Synchronized
    fun get(matchId: UUID): MatchSnapshot? = matches[matchId]

    /** 同一场地同时只允许一场未收尾的比赛（PLAN.md §6.2）。 */
    @Synchronized
    fun activeForRegion(regionId: String): MatchSnapshot? =
        matches.values.firstOrNull { it.regionId == regionId && it.phase != MatchPhase.CLOSED }

    @Synchronized
    fun put(snapshot: MatchSnapshot) {
        matches[snapshot.matchId] = snapshot
        dirty = true
    }

    /** 记下某场地最近一次的结果；[MatchResult.regionId] 就是键。 */
    @Synchronized
    fun putResult(result: MatchResult) {
        lastResults[result.regionId] = result
        dirty = true
    }

    @Synchronized
    fun lastResult(regionId: String): MatchResult? = lastResults[regionId]

    @Synchronized
    fun remove(matchId: UUID) {
        if (matches.remove(matchId) != null) dirty = true
    }

    /** 原子的整文件写入；失败时内存状态保持不变，调用方据此中止开赛或保留待恢复记录。 */
    @Synchronized
    fun save(): Boolean = runCatching { write() }
        .onFailure { logger.warn("Failed to save ${file.name}; keeping match records in memory.", it) }
        .isSuccess

    @Synchronized
    fun persist(snapshot: MatchSnapshot): Boolean {
        put(snapshot)
        return save()
    }

    @Synchronized
    override fun reload() {
        val loaded = LinkedHashMap<UUID, MatchSnapshot>()
        if (file.exists()) {
            val yaml = YamlConfiguration().apply { load(file) }
            val version = yaml.getInt("match-store-version", -1)
            require(version == MATCH_STORE_VERSION) {
                "Unsupported match store version $version; expected $MATCH_STORE_VERSION."
            }
            val root = yaml.getConfigurationSection("matches")
            if (root != null) {
                for (key in root.getKeys(false)) {
                    val matchId = try {
                        UUID.fromString(key)
                    } catch (ex: IllegalArgumentException) {
                        throw IllegalStateException("Invalid match id '$key' in ${file.name}.", ex)
                    }
                    val section = root.getConfigurationSection(key)
                        ?: throw IllegalStateException("Match record $key is not a section.")
                    loaded[matchId] = decode(matchId, section)
                }
            }
        }
        val loadedResults = LinkedHashMap<String, MatchResult>()
        if (file.exists()) {
            val yaml = YamlConfiguration().apply { load(file) }
            yaml.getConfigurationSection("results")?.let { root ->
                for (regionId in root.getKeys(false)) {
                    val entry = root.getConfigurationSection(regionId) ?: continue
                    loadedResults[regionId] = decodeResult(regionId, entry)
                }
            }
        }
        matches.clear()
        matches.putAll(loaded)
        lastResults.clear()
        lastResults.putAll(loadedResults)
        dirty = false
    }

    @Synchronized
    override fun close() {
        if (dirty) save()
    }

    private fun write() {
        val yaml = YamlConfiguration()
        yaml["match-store-version"] = MATCH_STORE_VERSION
        for ((matchId, snapshot) in matches) {
            val path = "matches.$matchId"
            yaml["$path.region"] = snapshot.regionId
            yaml["$path.revision"] = snapshot.publishedRevision
            yaml["$path.mode"] = snapshot.modeType
            yaml["$path.phase"] = snapshot.phase.name
            yaml["$path.round"] = snapshot.round
            yaml["$path.created-at"] = snapshot.createdAtMillis
            for ((key, value) in snapshot.options) {
                yaml["$path.options.$key"] = value
            }
            for ((teamId, team) in snapshot.teams) {
                yaml["$path.teams.$teamId.name"] = team.name
                yaml["$path.teams.$teamId.nation"] = team.nationId
            }
            snapshot.participants.forEachIndexed { index, participant ->
                val base = "$path.participants.$index"
                yaml["$base.id"] = participant.playerId.toString()
                yaml["$base.name"] = participant.name
                yaml["$base.team"] = participant.teamId
                yaml["$base.state"] = participant.state.name
                yaml["$base.eliminated-order"] = participant.eliminatedOrder
                yaml["$base.elimination-reason"] = participant.eliminationReason
            }
            snapshot.result?.let { result ->
                val base = "$path.result"
                yaml["$base.id"] = result.resultId.toString()
                yaml["$base.outcome"] = result.outcome.name
                yaml["$base.winners"] = result.winnerIds.map(UUID::toString)
                yaml["$base.winner-team"] = result.winnerTeamId
                yaml["$base.reason"] = result.reasonKey
                for ((key, value) in result.reasonArgs) {
                    yaml["$base.reason-args.$key"] = value
                }
                yaml["$base.forced-by"] = result.forcedBy
                yaml["$base.reward"] = result.rewardState.name
                yaml["$base.finished-at"] = result.finishedAtMillis
                yaml["$base.elimination-order"] = result.eliminationOrder.map(UUID::toString)
                yaml["$base.standings"] = result.standings.map(UUID::toString)
            }
            yaml["$path.pending-restore"] = snapshot.pendingRestore.map(UUID::toString)
            yaml["$path.confirmed-restore"] = snapshot.confirmedRestore.map(UUID::toString)
        }
        for ((regionId, result) in lastResults) {
            val base = "results.$regionId"
            yaml["$base.id"] = result.resultId.toString()
            yaml["$base.match"] = result.matchId.toString()
            yaml["$base.mode"] = result.modeType
            yaml["$base.outcome"] = result.outcome.name
            yaml["$base.winners"] = result.winnerIds.map(UUID::toString)
            yaml["$base.winner-team"] = result.winnerTeamId
            yaml["$base.reason"] = result.reasonKey
            for ((key, value) in result.reasonArgs) {
                yaml["$base.reason-args.$key"] = value
            }
            yaml["$base.forced-by"] = result.forcedBy
            yaml["$base.reward"] = result.rewardState.name
            yaml["$base.finished-at"] = result.finishedAtMillis
            yaml["$base.elimination-order"] = result.eliminationOrder.map(UUID::toString)
            yaml["$base.standings"] = result.standings.map(UUID::toString)
        }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            yaml.save(temporary)
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun decode(matchId: UUID, section: ConfigurationSection): MatchSnapshot {
        val teams = LinkedHashMap<String, TeamSnapshot>()
        section.getConfigurationSection("teams")?.let { teamSection ->
            for (teamId in teamSection.getKeys(false)) {
                val entry = teamSection.getConfigurationSection(teamId) ?: continue
                teams[teamId] = TeamSnapshot(
                    id = teamId,
                    name = entry.getString("name", teamId) ?: teamId,
                    nationId = entry.getString("nation"),
                )
            }
        }
        val participants = ArrayList<MatchParticipant>()
        section.getMapList("participants").forEach { raw ->
            participants += MatchParticipant(
                playerId = UUID.fromString(raw["id"]?.toString() ?: error("Match $matchId: participant id missing")),
                name = raw["name"]?.toString().orEmpty(),
                teamId = raw["team"]?.toString(),
                state = ParticipantState.valueOf(raw["state"]?.toString() ?: error("Match $matchId: participant state missing")),
                eliminatedOrder = (raw["eliminated-order"] as? Number)?.toInt(),
                eliminationReason = raw["elimination-reason"]?.toString(),
            )
        }
        val result = section.getConfigurationSection("result")?.let { entry ->
            MatchResult(
                resultId = UUID.fromString(entry.getString("id") ?: error("Match $matchId: result id missing")),
                matchId = matchId,
                regionId = section.getString("region").orEmpty(),
                modeType = section.getString("mode").orEmpty(),
                outcome = MatchOutcome.valueOf(entry.getString("outcome") ?: error("Match $matchId: result outcome missing")),
                winnerIds = entry.getStringList("winners").map(UUID::fromString).toSet(),
                winnerTeamId = entry.getString("winner-team"),
                reasonKey = entry.getString("reason").orEmpty(),
                reasonArgs = entry.getConfigurationSection("reason-args")
                    ?.getValues(false)
                    ?.mapValues { it.value?.toString().orEmpty() }
                    .orEmpty(),
                forcedBy = entry.getString("forced-by"),
                rewardState = entry.getString("reward")
                    ?.let { runCatching { RewardState.valueOf(it) }.getOrNull() }
                    ?: RewardState.NONE,
                finishedAtMillis = entry.getLong("finished-at"),
                eliminationOrder = entry.getStringList("elimination-order").map(UUID::fromString),
                standings = entry.getStringList("standings").map(UUID::fromString),
            )
        }
        val options = section.getConfigurationSection("options")
            ?.getValues(false)
            ?.mapValues { it.value?.toString().orEmpty() }
            .orEmpty()
        return MatchSnapshot(
            matchId = matchId,
            regionId = section.getString("region").orEmpty(),
            publishedRevision = section.getLong("revision"),
            modeType = section.getString("mode").orEmpty(),
            createdAtMillis = section.getLong("created-at"),
            options = options,
            participants = participants,
            teams = teams,
            phase = MatchPhase.valueOf(section.getString("phase") ?: error("Match $matchId: phase missing")),
            round = section.getInt("round", 1),
            result = result,
            pendingRestore = section.getStringList("pending-restore").map(UUID::fromString).toSet(),
            confirmedRestore = section.getStringList("confirmed-restore").map(UUID::fromString).toSet(),
        )
    }

    /** `results.<regionId>` 一条记录；与活动比赛里的 `result` 同形，只是 regionId 来自键。 */
    private fun decodeResult(regionId: String, entry: ConfigurationSection): MatchResult = MatchResult(
        resultId = UUID.fromString(entry.getString("id") ?: error("Result $regionId: id missing")),
        matchId = UUID.fromString(entry.getString("match") ?: error("Result $regionId: match missing")),
        regionId = regionId,
        modeType = entry.getString("mode").orEmpty(),
        outcome = MatchOutcome.valueOf(entry.getString("outcome") ?: error("Result $regionId: outcome missing")),
        winnerIds = entry.getStringList("winners").map(UUID::fromString).toSet(),
        winnerTeamId = entry.getString("winner-team"),
        reasonKey = entry.getString("reason").orEmpty(),
        reasonArgs = entry.getConfigurationSection("reason-args")
            ?.getValues(false)
            ?.mapValues { it.value?.toString().orEmpty() }
            .orEmpty(),
        forcedBy = entry.getString("forced-by"),
        rewardState = entry.getString("reward")
            ?.let { runCatching { RewardState.valueOf(it) }.getOrNull() }
            ?: RewardState.NONE,
        finishedAtMillis = entry.getLong("finished-at"),
        eliminationOrder = entry.getStringList("elimination-order").map(UUID::fromString),
        // 旧文件没有这一段，读出来是空列表：结果页少一行名次，不会读错胜负。
        standings = entry.getStringList("standings").map(UUID::fromString),
    )

    private companion object {
        const val MATCH_STORE_VERSION = 1
    }
}
