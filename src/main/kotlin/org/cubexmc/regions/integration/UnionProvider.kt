package org.cubexmc.regions.integration

import org.cubexmc.regions.model.UnionRef
import java.util.UUID

/**
 * 工会／国家数据来源。
 *
 * [getUnion] 是历史语义（"玩家所属的那一个工会"，取所属 Land 的第一项），条件判断和历史奖励映射
 * 仍用它；工会战分队必须用 [getUnions]，因为玩家可能同时属于多个 Land、多个 Nation
 * （PLAN.md §7.2「不能使用 firstOrNull() 或把独立 Land 自动当成 Nation」）。
 */
interface UnionProvider {
    val type: String

    fun isAvailable(): Boolean

    /** 历史语义：玩家所属的单个工会；没有返回 null。 */
    fun getUnion(playerId: UUID): UnionRef?

    /**
     * 完整候选：玩家拥有或加入的全部 Land 的非空 Nation，按稳定 ID 去重。
     * 默认实现退化为 [getUnion]，厂商实现应给出完整集合。
     */
    fun getUnions(playerId: UUID): List<UnionRef> = listOfNotNull(getUnion(playerId))

    /**
     * 数据来源当前不可用的原因码（用于把"查询失败"和"确实没有 Nation"分开表示，
     * PLAN.md §7.2「依赖异常」）。可用或确实没有工会时返回 null。
     */
    fun unavailableReason(): String? = null

    /** 服务器上已知的全部工会/国家，用于选队补全；无法枚举时返回空列表。 */
    fun allUnions(): List<UnionRef> = emptyList()

    /**
     * 玩家"当下正在管的那个领地"所属的工会——Lands 里就是 `/l edit` 选定的 Land 的 Nation。
     *
     * 专供选队时省输入用：场地主多半就是其中一方，没必要再把自己国家的名字敲一遍。
     * 返回 null 的情形都是"推导不出来"：没选领地、领地不属于任何国家、API 不可用——
     * 调用方应当回退到让人明确选，**不得猜**。
     */
    fun getEditUnion(playerId: UUID): UnionRef? = null

    /**
     * 两个 Nation 之间是否处于敌对关系（PLAN.md §7.2 高级 `enemy-only`）。
     *
     * 返回 `null` 表示**无法验证**（API 不支持、对象解析不到、关系未知）。调用方必须按
     * "无法验证就不开赛"处理，不能把 null 当成非敌对放行。
     */
    fun areNationsEnemy(a: String, b: String): Boolean? = null

    fun areSameUnion(a: UUID, b: UUID): Boolean

    fun areAllied(a: UUID, b: UUID): Boolean

    fun areEnemies(a: UUID, b: UUID): Boolean

    fun placeholder(playerId: UUID, key: String): String?
}
