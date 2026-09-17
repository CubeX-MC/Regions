package org.cubexmc.regions.integration

class UnionProviderRegistry {
    private val providers: MutableMap<String, UnionProvider> = LinkedHashMap()
    private var preferred: String = "lands"

    fun register(provider: UnionProvider) {
        providers[provider.type.lowercase()] = provider
    }

    fun setPreferred(type: String) {
        preferred = type.lowercase()
    }

    fun active(): UnionProvider? =
        providers[preferred]?.takeIf { it.isAvailable() }
            ?: providers.values.firstOrNull { it.isAvailable() }

    fun all(): Collection<UnionProvider> = providers.values.toList()
}

/**
 * 后备来源：永远“可用”，但识别不出任何工会。工会战据此拒绝开赛并指出需要可用的工会数据来源，
 * 而不是把每个人当成独立阵营降级成个人战（PLAN.md §7.2）。
 */
class FallbackUnionProvider : UnionProvider {
    override val type: String = "fallback"

    override fun isAvailable(): Boolean = true

    override fun getUnion(playerId: java.util.UUID) = null

    override fun getUnions(playerId: java.util.UUID): List<org.cubexmc.regions.model.UnionRef> = emptyList()

    override fun unavailableReason(): String = REASON_NO_PROVIDER

    override fun areSameUnion(a: java.util.UUID, b: java.util.UUID): Boolean = false

    override fun areAllied(a: java.util.UUID, b: java.util.UUID): Boolean = false

    override fun areEnemies(a: java.util.UUID, b: java.util.UUID): Boolean = false

    override fun placeholder(playerId: java.util.UUID, key: String): String? = null

    companion object {
        /** 稳定原因码：工会战用它区分"依赖不可用"与"玩家没有国家"。 */
        const val REASON_NO_PROVIDER = "union-provider-unavailable"
    }
}
