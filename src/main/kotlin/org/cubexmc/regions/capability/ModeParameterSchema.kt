package org.cubexmc.regions.capability

import java.util.Locale

/**
 * 每种玩法**自己**接受的参数。
 *
 * 在这之前 8 种玩法共用一份 40 多键的参数袋且 `strict = false`，后果是：决斗能设
 * `seeker-ratio`，工会战能设 `checkpoint-vehicles`，竞速能设 `kit=DIAMOND_SWORD` ——
 * 前两个只是噪音，第三个是**静默失败**：校验通过、发布成功，而 `RaceModeService`
 * 从来不读那个键。README 承诺的 "fail closed" 被参数模型本身破坏了。
 *
 * 现在每种玩法只声明自己真的读取的键，且一律 `strict = true`：写错的键在校验阶段
 * 就被点名，而不是等到开赛才发现装备没换。新增玩法参数时**先**在这里加，
 * 再去服务里读——[ModeParameterSchemaTest] 会把"服务读了但 schema 没声明"钉死。
 *
 * 命名约定：这里只放稳定的配置标识（ASCII），显示名走 `labels.field.*`。
 */
internal object ModeParameterSchema {

    /** 注册顺序即 GUI 与命令补全里的玩法顺序。 */
    val ALL_MODES: List<String> = listOf(
        "free_event",
        "dual_pvp",
        "union_war",
        "free_for_all",
        "run_race",
        "boat_race",
        "horse_race",
        "hide_and_seek",
    )

    /** 战斗三兄弟：共用 `match` 状态机、装备托管与伤害隔离。 */
    val COMBAT_MODES: Set<String> = setOf("dual_pvp", "union_war", "free_for_all")

    /** 三种竞速：共用 `RaceModeService`。 */
    val RACE_MODES: Set<String> = setOf("run_race", "boat_race", "horse_race")

    /** 回合制（捉迷藏）：`RoundModeService`。 */
    val ROUND_MODES: Set<String> = setOf("hide_and_seek")

    /** 竞速阶段能要求的载具状态；`pass` 表示不检查。 */
    val VEHICLES: Set<String> = setOf(
        "none", "on_foot", "on-foot", "no_vehicle", "no-vehicle", "foot",
        "any", "vehicle", "any_vehicle", "any-vehicle", "boat", "horse", "minecart",
        "pig", "strider", "camel", "donkey", "mule", "llama",
        "pass", "ignore", "any_state", "any-state",
    )

    fun parametersFor(modeType: String): List<ParameterDescriptor> =
        when (modeType.lowercase(Locale.ROOT)) {
            "free_event" -> FREE_EVENT
            "dual_pvp" -> DUAL_PVP
            "union_war" -> UNION_WAR
            "free_for_all" -> FREE_FOR_ALL
            "run_race", "boat_race", "horse_race" -> RACE
            "hide_and_seek" -> HIDE_AND_SEEK
            else -> emptyList()
        }

    // ---------------------------------------------------------------- 参数组

    /**
     * 返回点／出场点。三个历史键是**同一个概念**的回退链
     * （`respawn` → `outside` → `spectator`），所以声明成一个键加两个别名，
     * 而不是三个互不相干的键。
     */
    private val returnPoint = string("respawn", aliases = setOf("outside", "spectator"))

    /** 报名与开赛门槛。 */
    private val roster = listOf(
        integer("min-players", min = 1.0),
        integer("max-players", min = 0.0),
        bool("require-ready"),
    )

    /** 玩家自行准备／裁判发令；只有 Race 与 Round 用投票开赛。 */
    private val voteStart = listOf(
        enum("start-mode", setOf("vote", "judge")),
        decimal("vote-start-percent", min = 0.0, max = 1.0),
    )

    /**
     * 临时装备。三类玩法现在走同一个 [org.cubexmc.regions.mode.ModeKit]，
     * 因此 `armor` / `offhand` 在竞速和捉迷藏里也**真的**生效。
     */
    private val gear = listOf(
        bool("replace-gear"),
        string("kit"),
        string("armor"),
        string("offhand"),
    )

    /** 开赛屏障与倒计时（战斗三兄弟共用）。 */
    private val combatTiming = listOf(
        integer("preparing-seconds", min = 1.0, max = 60.0),
        integer("countdown-seconds", min = 1.0, max = 30.0),
    )

    /** 可选的 Contract 奖励托管。 */
    private val reward = listOf(
        string("reward-source"),
        string("reward-contract"),
    )

    // ---------------------------------------------------------------- 各玩法

    /**
     * 自由活动：**没有比赛**，没有报名、胜负或装备托管。
     * 它只是"一块带规则／效果／触发动作的场地"，唯一有意义的点位是
     * 触发动作里 `teleport: respawn` 能引用的返回点。
     */
    private val FREE_EVENT: List<ParameterDescriptor> = listOf(returnPoint)

    private val DUAL_PVP: List<ParameterDescriptor> =
        roster + gear + combatTiming + reward + listOf(
            returnPoint,
            string("spawn-points"),
            integer("best-of", min = 1.0, max = 3.0),
            integer("round-seconds", min = 1.0),
            integer("intermission-seconds", min = 0.0, max = 60.0),
        )

    private val UNION_WAR: List<ParameterDescriptor> =
        roster + gear + combatTiming + reward + listOf(
            returnPoint,
            string("spawn-points"),
            string("spawn-points-b"),
            integer("team-size", min = 2.0, max = 10.0),
            integer("min-unions", min = 2.0),
            integer("timeout-seconds", min = 1.0),
            enum("diplomacy", setOf("agreed", "enemy-only")),
        )

    /**
     * 大乱斗不接奖励。这两个键**仍然留在 schema 里**是刻意的：
     * 留着才能由 `RegionValidationService` 给出"大乱斗没有奖励集成，请移除
     * reward-source/reward-contract"这条带修复入口的具体错误，
     * 而不是退化成通用的"未知参数"。错误信息本身就是产品的一部分。
     */
    private val FREE_FOR_ALL: List<ParameterDescriptor> =
        roster + gear + combatTiming + reward + listOf(
            returnPoint,
            string("spawn-points"),
            integer("timeout-seconds", min = 1.0),
        )

    private val RACE: List<ParameterDescriptor> =
        roster + voteStart + gear + listOf(
            returnPoint,
            string("start"),
            string("finish"),
            string("checkpoints"),
            bool("require-start"),
            bool("teleport-start"),
            decimal("radius", min = 0.1),
            decimal("start-radius", min = 0.1),
            decimal("checkpoint-radius", min = 0.1),
            decimal("finish-radius", min = 0.1),
            // 历史上三个键写法并存且优先级相同，合成一个键加别名，避免"设了一个没生效"。
            integer("timeout-seconds", min = 0.0, aliases = setOf("max-duration-seconds", "duration-seconds")),
            enum("vehicle", VEHICLES),
            enum("start-vehicle", VEHICLES),
            enum("finish-vehicle", VEHICLES),
            string("checkpoint-vehicles", allowBlank = true),
        )

    private val HIDE_AND_SEEK: List<ParameterDescriptor> =
        roster + voteStart + gear + listOf(
            returnPoint,
            string("seeker-kit"),
            string("hider-kit"),
            integer("seekers", min = 1.0),
            decimal("seeker-ratio", min = 0.05, max = 0.8),
            integer("hide-seconds", min = 0.0),
            integer("round-seconds", min = 1.0),
            bool("found-becomes-seeker"),
        )

    // ---------------------------------------------------------------- helpers

    private fun string(
        key: String,
        required: Boolean = false,
        aliases: Set<String> = emptySet(),
        allowBlank: Boolean = false,
    ) = ParameterDescriptor(key, ParameterType.STRING, required, aliases, allowBlank = allowBlank)

    private fun integer(
        key: String,
        min: Double? = null,
        max: Double? = null,
        aliases: Set<String> = emptySet(),
    ) = ParameterDescriptor(key, ParameterType.INTEGER, false, aliases, min = min, max = max)

    private fun decimal(key: String, min: Double? = null, max: Double? = null) =
        ParameterDescriptor(key, ParameterType.DECIMAL, false, min = min, max = max)

    private fun bool(key: String) = ParameterDescriptor(key, ParameterType.BOOLEAN)

    private fun enum(key: String, allowed: Set<String>) =
        ParameterDescriptor(key, ParameterType.ENUM, false, allowedValues = allowed)
}
