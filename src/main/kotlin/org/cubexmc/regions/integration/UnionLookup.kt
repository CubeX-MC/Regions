package org.cubexmc.regions.integration

import org.cubexmc.regions.model.UnionRef
import java.util.Locale

/**
 * 把服主输入的一行文字解析成某个工会（Nation）。
 *
 * Lands 的 Nation 主键是 ULID（`01J9X8...` 这种 26 位串），照抄一遍既难念也容易错。
 * 这里允许三种更省事的写法，且**都不牺牲确定性**：编号、名字、名字前缀；
 * 只有唯一命中才算数，命中多个一律返回 [Resolution.Ambiguous] 让调用方把候选列出来，
 * 绝不替服主在两个工会之间挑一个。
 *
 * 纯函数、不碰 Bukkit 与 Lands，所以 `UnionLookupTest` 能穷举这些组合。
 */
object UnionLookup {

    sealed interface Resolution {
        data class Found(val union: UnionRef) : Resolution

        /** 输入同时匹配多个工会；调用方应把 [matches] 原样展示给服主。 */
        data class Ambiguous(val matches: List<UnionRef>) : Resolution

        data object NotFound : Resolution
    }

    /** 去掉 `&x` / `§x` 颜色码与首尾空白——展示与匹配都用这个形态。 */
    fun plainName(raw: String): String = COLOR_CODE.replace(raw, "").trim()

    /**
     * 可比较、可补全的形态：去色、小写、空白与连字符归一成下划线。
     * 补全项必须是单个 token，否则带空格的工会名在命令里会被拆成两个参数。
     */
    fun token(raw: String): String =
        plainName(raw).lowercase(Locale.ROOT).replace(SEPARATORS, "_")

    /** 稳定顺序：先按去色名字，再按 id。编号选择就建立在这个顺序上。 */
    fun ordered(candidates: List<UnionRef>): List<UnionRef> =
        candidates.sortedWith(compareBy({ plainName(it.name).lowercase(Locale.ROOT) }, { it.id }))

    /**
     * 解析顺序：ULID 原样 → 列表编号（1 起） → 规范化全名 → 唯一的名字前缀。
     *
     * 编号优先于名字之后，是因为有的工会就叫“1”——先试 ULID 与全名，
     * 数字才被当成编号，这样纯数字名字仍然选得中。
     */
    fun resolve(input: String, candidates: List<UnionRef>): Resolution {
        val ordered = ordered(candidates)
        val trimmed = input.trim()
        if (trimmed.isEmpty() || ordered.isEmpty()) return Resolution.NotFound

        ordered.firstOrNull { it.id.equals(trimmed, ignoreCase = true) }?.let { return Resolution.Found(it) }

        val wanted = token(trimmed)
        val exactName = ordered.filter { token(it.name) == wanted }
        if (exactName.size == 1) return Resolution.Found(exactName.single())
        if (exactName.size > 1) return Resolution.Ambiguous(exactName)

        trimmed.toIntOrNull()?.let { index ->
            if (index in 1..ordered.size) return Resolution.Found(ordered[index - 1])
        }

        val prefixed = ordered.filter { token(it.name).startsWith(wanted) }
        return when {
            prefixed.size == 1 -> Resolution.Found(prefixed.single())
            prefixed.size > 1 -> Resolution.Ambiguous(prefixed)
            else -> Resolution.NotFound
        }
    }

    /** 补全候选：优先给名字 token，服主仍然可以直接粘 ULID。 */
    fun completions(candidates: List<UnionRef>): List<String> =
        ordered(candidates).map { token(it.name) }.filter { it.isNotBlank() }.distinct()

    private val COLOR_CODE = Regex("[§&][0-9a-fk-orA-FK-OR]")
    private val SEPARATORS = Regex("[ 	　-]+")
}
