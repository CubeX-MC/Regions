package org.cubexmc.regions.service

import org.cubexmc.regions.model.RegionDefinition
import java.util.Locale

/**
 * 让用户用**看得见的名字**指代场地，而不是背 Region ID。
 *
 * Region ID 是内部主键（存档、审计、revision、装备托管、资金 lease 全都引用它），不会改；
 * 但它不该出现在玩家和服主的日常操作里。可接受的写法：
 * 列表编号、场地名、Lands 领地名（`领地` 或 `领地/area`）、名字前缀，以及 ID 本身。
 *
 * **为什么还需要消歧**：同一块领地上确实可能存在多个 Region——校验只禁止
 * "两个**有状态玩法**的已发布 Region 重叠"（`overlap-stateful-mode`），
 * 一个 `free_event` 的氛围 Region 叠在竞技场上是合法的；草稿与已停用的更不受限制。
 * 所以命中多个时列出候选，绝不替用户挑。
 */
object RegionLookup {

    sealed interface Resolution {
        data class Found(val region: RegionDefinition) : Resolution

        data class Ambiguous(val matches: List<RegionDefinition>) : Resolution

        data object NotFound : Resolution
    }

    /** Lands 来源的"领地[/area]"标签；非 Lands 来源返回 null。 */
    fun landLabel(region: RegionDefinition): String? {
        if (!region.source.type.equals("lands", ignoreCase = true)) return null
        val land = region.source.values["land"]?.takeIf { it.isNotBlank() } ?: return null
        val area = region.source.values["area"]?.takeIf { it.isNotBlank() && it != "default" }
        return if (area == null) land else "$land/$area"
    }

    /**
     * 给人看的一行标识：场地名优先，后面缀上领地名。
     * 场地名与领地名相同时不重复显示——真机上多半就是这种情况。
     */
    fun display(region: RegionDefinition): String {
        val land = landLabel(region)
        val name = region.name.takeIf { it.isNotBlank() } ?: region.id
        return when {
            land == null -> name
            token(land) == token(name) -> name
            else -> "$name ($land)"
        }
    }

    fun ordered(regions: Collection<RegionDefinition>): List<RegionDefinition> =
        regions.sortedWith(compareBy({ token(it.name.ifBlank { it.id }) }, { it.id }))

    /**
     * 解析顺序：ID → 场地名 → 领地名（含 `领地/area`）→ 列表编号 → 唯一前缀。
     * 编号排在名字之后，这样真的叫"1"的场地仍然选得中。
     */
    fun resolve(input: String, regions: Collection<RegionDefinition>): Resolution {
        val ordered = ordered(regions)
        val trimmed = input.trim()
        if (trimmed.isEmpty() || ordered.isEmpty()) return Resolution.NotFound

        ordered.firstOrNull { it.id.equals(trimmed, ignoreCase = true) }?.let { return Resolution.Found(it) }

        val wanted = token(trimmed)
        exactly(ordered.filter { token(it.name) == wanted })?.let { return it }
        exactly(ordered.filter { landLabel(it)?.let(::token) == wanted })?.let { return it }
        // 只写领地名时，同一领地下的多个 area 都算命中。
        exactly(ordered.filter { token(it.source.values["land"].orEmpty()) == wanted })?.let { return it }

        trimmed.toIntOrNull()?.let { index ->
            if (index in 1..ordered.size) return Resolution.Found(ordered[index - 1])
        }

        return exactly(ordered.filter { candidate ->
            token(candidate.name).startsWith(wanted) || landLabel(candidate)?.let(::token)?.startsWith(wanted) == true
        }) ?: Resolution.NotFound
    }

    /** 补全项：场地名与领地名都给，且都是单 token，不会被命令参数切开。 */
    fun completions(regions: Collection<RegionDefinition>): List<String> =
        ordered(regions)
            .flatMap { listOfNotNull(token(it.name), landLabel(it)?.let(::token)) }
            .filter { it.isNotBlank() }
            .distinct()

    fun token(raw: String): String =
        raw.trim().lowercase(Locale.ROOT).replace(SEPARATORS, "_")

    private fun exactly(matches: List<RegionDefinition>): Resolution? = when {
        matches.size == 1 -> Resolution.Found(matches.single())
        matches.size > 1 -> Resolution.Ambiguous(matches)
        else -> null
    }

    private val SEPARATORS = Regex("[ \t\u3000-]+")
}
