package org.cubexmc.regions.match

import java.util.Locale

/**
 * 比赛进行中允许哪些指令（PLAN.md §6.3）。
 *
 * 战斗玩法里，指令就是最短的逃跑通道：`/spawn`、`/home`、`/tp`、`/l edit`……
 * 一条命令就能让对手打空。场地 Flag 里的 `commands` 规则是**服主可配的场地规则**，
 * 既要有人记得配，也会被 `regions.bypass.flags` 绕过；比赛内部的保护不能依赖它
 * （§6.3.1「比赛内部保护不受场地 Flag bypass 影响」），所以这里是一道独立的默认封锁。
 *
 * 默认只放行插件自己的根指令——选手要能 `/regions game status`、`/regions game leave`。
 * 服主可以在 `modes.allowed-commands` 里加自己认为安全的（例如聊天类）。
 */
object MatchCommandPolicy {

    /** 任何配置下都放行：否则选手会被关在比赛里，连退出都做不到。 */
    val ALWAYS_ALLOWED: Set<String> = setOf("regions", "region", "venue")

    /**
     * @param rootLabel 玩家输入的根指令（不含 `/`，可能带插件前缀 `plugin:cmd`）
     * @param allowed `modes.allowed-commands` 的配置值
     */
    fun isBlocked(rootLabel: String, allowed: Collection<String>): Boolean {
        val label = normalize(rootLabel)
        if (label.isEmpty()) return false
        if (ALWAYS_ALLOWED.contains(label)) return false
        return allowed.none { normalize(it) == label }
    }

    /**
     * `plugin:command` 形式要按**去掉命名空间后**的名字判定，否则 `/lands:l edit`
     * 就能绕过对 `/l` 的封锁。
     */
    fun normalize(raw: String): String =
        raw.trim().removePrefix("/").substringBefore(' ').substringAfterLast(':').lowercase(Locale.ROOT)
}
