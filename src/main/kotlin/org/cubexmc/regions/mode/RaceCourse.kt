package org.cubexmc.regions.mode

import java.util.Locale

/**
 * 赛道本身的纯逻辑：载具约束、阶段取值、超时与半径。
 *
 * 从 `RaceModeService` 拆出来是为了能**不起服务器**地穷举测试：载具别名有二十多个写法，
 * 检查点可以逐点指定不同载具，超时有三个历史键名，这些都是靠读代码保证不了的地方。
 */
object RaceCourse {

    /** 点位半径缺省值（格）。 */
    const val DEFAULT_RADIUS = 2.5

    /** 未配置超时时的默认时限（秒）。 */
    const val DEFAULT_TIMEOUT_SECONDS = 300L

    /** 载具约束的语义分类；同一类里的多种写法是历史别名，不是不同行为。 */
    enum class Constraint {
        /** 不检查载具。 */
        IGNORE,

        /** 必须**不在**任何载具上。 */
        ON_FOOT,

        /** 必须在任意载具上。 */
        ANY_VEHICLE,

        /** 必须在指定类型的载具上。 */
        SPECIFIC,
    }

    fun classify(raw: String): Constraint =
        when (raw.lowercase(Locale.ROOT)) {
            "pass", "ignore", "any_state", "any-state" -> Constraint.IGNORE
            "none", "on_foot", "on-foot", "no_vehicle", "no-vehicle", "foot" -> Constraint.ON_FOOT
            "any", "vehicle", "any_vehicle", "any-vehicle" -> Constraint.ANY_VEHICLE
            else -> Constraint.SPECIFIC
        }

    /**
     * 载具是否满足约束。
     *
     * @param vehicleType 玩家当前载具的实体类型名（`player.vehicle?.type?.name`），不在载具上传 null
     */
    fun matches(vehicleType: String?, raw: String): Boolean {
        val type = vehicleType?.lowercase(Locale.ROOT).orEmpty()
        return when (classify(raw)) {
            Constraint.IGNORE -> true
            Constraint.ON_FOOT -> vehicleType == null
            Constraint.ANY_VEHICLE -> vehicleType != null
            Constraint.SPECIFIC -> vehicleType != null && matchesSpecific(type, raw.lowercase(Locale.ROOT))
        }
    }

    /**
     * 具体载具类型的匹配。
     *
     * `boat` / `horse` / `minecart` / `llama` 用**包含**匹配：原版把它们拆成了
     * `OAK_BOAT`、`ACACIA_CHEST_BOAT`、`ZOMBIE_HORSE`、`TRADER_LLAMA` 等一堆类型，
     * 服主写 `vehicle: boat` 时想要的是"一条船"，不是"恰好是 OAK_BOAT"。
     * 其余按类型名精确匹配。
     *
     * 竹筏是个特例：`BAMBOO_RAFT` / `BAMBOO_CHEST_RAFT` 的名字里**没有** `boat`，
     * 只按包含 `boat` 匹配的话，划着竹筏的选手永远过不了检查点和终点——
     * 这是 [RaceCourseTest] 抓出来的既有缺陷。
     */
    private fun matchesSpecific(type: String, constraint: String): Boolean =
        when (constraint) {
            "boat" -> type.contains("boat") || type.contains("raft")
            "horse", "minecart", "llama" -> type.contains(constraint)
            else -> type == constraint
        }

    /**
     * 某个阶段实际生效的载具约束。
     *
     * 优先级：该检查点的逐点约束 → 该阶段的 `<stage>-vehicle` → 全局 `vehicle` → 玩法默认值。
     */
    fun constraintFor(
        values: Map<String, String>,
        modeType: String?,
        stage: Stage,
        checkpointIndex: Int = 0,
    ): String {
        if (stage == Stage.CHECKPOINT) {
            val perPoint = parseConstraintList(values["checkpoint-vehicles"]).getOrNull(checkpointIndex)
            if (!perPoint.isNullOrBlank()) return perPoint
        }
        values["${stage.key}-vehicle"]?.takeIf { it.isNotBlank() }?.let { return it }
        values["vehicle"]?.takeIf { it.isNotBlank() }?.let { return it }
        return defaultConstraint(modeType)
    }

    enum class Stage(val key: String) {
        START("start"),
        CHECKPOINT("checkpoint"),
        FINISH("finish"),
    }

    fun defaultConstraint(modeType: String?): String =
        when (modeType?.lowercase(Locale.ROOT)) {
            "boat_race" -> "boat"
            "horse_race" -> "horse"
            "run_race" -> "none"
            else -> "pass"
        }

    fun parseConstraintList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val delimiter = if (raw.contains(';')) ';' else ','
        return raw.split(delimiter).map { it.trim() }
    }

    /**
     * 时限（秒）。三个历史键同义，按 `timeout-seconds` → `max-duration-seconds` →
     * `duration-seconds` 取第一个存在的；一个都没有时用 [DEFAULT_TIMEOUT_SECONDS]。
     * 0 表示不限时。
     */
    fun timeoutSeconds(values: Map<String, String>): Long =
        (values["timeout-seconds"] ?: values["max-duration-seconds"] ?: values["duration-seconds"])
            ?.toLongOrNull()
            ?.coerceAtLeast(0L)
            ?: DEFAULT_TIMEOUT_SECONDS

    /** 某个点位的判定半径：专用键优先，其次通用 `radius`，最后默认值。 */
    fun radius(values: Map<String, String>, key: String): Double =
        values[key]?.toDoubleOrNull()
            ?: values["radius"]?.toDoubleOrNull()
            ?: DEFAULT_RADIUS

    /** `vote` 模式下开赛所需的准备人数。 */
    fun requiredVotes(values: Map<String, String>, rosterSize: Int): Int {
        val percent = values["vote-start-percent"]?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 1.0
        return kotlin.math.ceil(rosterSize * percent).toInt().coerceAtLeast(1)
    }

    fun startMode(values: Map<String, String>): String =
        values["start-mode"]?.lowercase(Locale.ROOT) ?: "vote"

    fun minPlayers(values: Map<String, String>): Int =
        values["min-players"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1

    /** 0 表示不限。 */
    fun maxPlayers(values: Map<String, String>): Int =
        values["max-players"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    /**
     * 载具约束对应的显示用语言键；认不出的自定义写法返回 null，
     * 由调用方原样显示——那是服主自己写的内容，不是需要翻译的内部术语。
     */
    fun constraintLabelKey(raw: String): String? =
        when (raw.lowercase(Locale.ROOT)) {
            "pass", "ignore", "any_state", "any-state" -> "gui.vehicle.ignore"
            "none", "on_foot", "on-foot", "no_vehicle", "no-vehicle", "foot" -> "gui.vehicle.on-foot"
            "any", "vehicle", "any_vehicle", "any-vehicle" -> "gui.vehicle.any"
            "boat" -> "gui.vehicle.boat"
            "horse" -> "gui.vehicle.horse"
            "minecart" -> "gui.vehicle.minecart"
            "pig" -> "game.vehicle.pig"
            "strider" -> "game.vehicle.strider"
            "camel" -> "game.vehicle.camel"
            "donkey" -> "game.vehicle.donkey"
            "mule" -> "game.vehicle.mule"
            "llama" -> "game.vehicle.llama"
            else -> null
        }

    /** 准备阶段被拒绝时该说哪一句。 */
    fun requireMessageKey(raw: String): String =
        when (classify(raw)) {
            Constraint.ON_FOOT -> "game.race.require.on-foot"
            Constraint.ANY_VEHICLE -> "game.race.require.any"
            Constraint.IGNORE -> "game.race.require.any"
            Constraint.SPECIFIC -> when (raw.lowercase(Locale.ROOT)) {
                "boat" -> "game.race.require.boat"
                "horse" -> "game.race.require.horse"
                else -> "game.race.require.other"
            }
        }
}
