package org.cubexmc.regions.capability

object BuiltInRegionCapabilities {
    fun registerAll(catalog: CapabilityCatalog) {
        registerSources(catalog)
        registerModes(catalog)
        registerFlags(catalog)
        registerEffects(catalog)
        registerActions(catalog)
        registerConditions(catalog)
        registerTriggers(catalog)
    }

    /**
     * Only triggers with a runtime firing site belong here. `RegionsPlugin.verifyCapabilityCatalog`
     * compares this set against [org.cubexmc.regions.model.RegionTrigger], so a trigger that is
     * declared but never fired cannot reach a published revision.
     */
    private fun registerTriggers(catalog: CapabilityCatalog) {
        listOf(
            "on_enter",
            "on_leave",
            "on_death",
            "on_kill",
            "on_respawn",
            "on_interact",
            "on_command",
            "on_timer",
            "on_mode_start",
            "on_mode_end",
            "on_role_assigned",
            "on_found",
            "on_checkpoint",
            "on_finish",
        ).forEach { id ->
            catalog.register(descriptor(CapabilityKind.TRIGGER, id))
        }
    }

    private fun registerSources(catalog: CapabilityCatalog) {
        catalog.register(descriptor(
            CapabilityKind.SOURCE,
            "lands",
            parameters = listOf(string("land", required = true), string("area", allowBlank = false)),
            requiredPlugins = setOf("Lands"),
        ))
        catalog.register(descriptor(
            CapabilityKind.SOURCE,
            "cuboid",
            risk = CapabilityRisk.MEDIUM,
            strict = false,
            parameters = listOf(
                string("id"), string("name"), string("world", required = true),
                decimal("min-x", required = true), decimal("min-y", required = true), decimal("min-z", required = true),
                decimal("max-x", required = true), decimal("max-y", required = true), decimal("max-z", required = true),
            ),
        ))
    }

    /**
     * 每种玩法只注册**它自己**读取的参数，且一律严格校验：写错或写到别的玩法上的键
     * 会在校验阶段被点名，而不是通过校验后在运行时被静默忽略。
     * 参数表在 [ModeParameterSchema]。
     */
    private fun registerModes(catalog: CapabilityCatalog) {
        for (id in ModeParameterSchema.ALL_MODES) {
            catalog.register(descriptor(
                CapabilityKind.MODE,
                id,
                parameters = ModeParameterSchema.parametersFor(id),
            ))
        }
    }

    private fun registerFlags(catalog: CapabilityCatalog) {
        val rule = listOf(enum("value", setOf("allow", "deny", "pass"), required = true))
        listOf("pvp", "fly", "vanish", "item_drop", "item_pickup").forEach { id ->
            catalog.register(descriptor(CapabilityKind.FLAG, id, parameters = rule))
        }
        catalog.register(descriptor(
            CapabilityKind.FLAG,
            "commands",
            parameters = listOf(
                enum("value", setOf("allow", "deny", "pass", "allowlist", "blocklist"), required = true),
                enum("mode", setOf("allow", "deny", "pass", "allowlist", "blocklist")),
                string("values", aliases = setOf("commands")),
            ),
        ))
    }

    private fun registerEffects(catalog: CapabilityCatalog) {
        catalog.register(descriptor(
            CapabilityKind.EFFECT,
            "scale",
            parameters = listOf(decimal("value", true, 0.1, 4.0), integer("lease-duration-ticks", min = 1.0)),
        ))
        catalog.register(descriptor(
            CapabilityKind.EFFECT,
            "potion",
            parameters = listOf(
                string("effect", required = true, aliases = setOf("name")),
                integer("duration-ticks", min = 1.0),
                integer("lease-duration-ticks", min = 1.0),
                integer("amplifier", min = 0.0, max = 255.0),
                bool("ambient"), bool("particles"), bool("icon"),
            ),
        ))
        catalog.register(descriptor(
            CapabilityKind.EFFECT,
            "walk_speed",
            parameters = listOf(decimal("value", true, -1.0, 1.0), integer("lease-duration-ticks", min = 1.0)),
        ))
        catalog.register(descriptor(
            CapabilityKind.EFFECT,
            "fly_speed",
            parameters = listOf(decimal("value", true, -1.0, 1.0), integer("lease-duration-ticks", min = 1.0)),
        ))
        catalog.register(descriptor(
            CapabilityKind.EFFECT,
            "allow_flight",
            parameters = listOf(
                bool("value", aliases = setOf("allow")),
                integer("lease-duration-ticks", min = 1.0),
            ),
        ))
        catalog.register(descriptor(
            CapabilityKind.EFFECT,
            "glowing",
            parameters = listOf(bool("value"), integer("lease-duration-ticks", min = 1.0)),
        ))
        catalog.register(descriptor(
            CapabilityKind.EFFECT,
            "invisibility_suppression",
            parameters = listOf(integer("lease-duration-ticks", min = 1.0)),
        ))
    }

    private fun registerActions(catalog: CapabilityCatalog) {
        // text/title/subtitle 与对应 *-key 键形式互斥且至少其一，由 RegionValidationService 判定；
        // schema 只声明接受的字段，required 让位给"二选一"规则。
        catalog.register(descriptor(CapabilityKind.ACTION, "message", parameters = listOf(string("text", aliases = setOf("message")), string("text-key"))))
        catalog.register(descriptor(CapabilityKind.ACTION, "broadcast", parameters = listOf(string("text", aliases = setOf("message")), string("text-key"))))
        catalog.register(descriptor(
            CapabilityKind.ACTION,
            "title",
            parameters = listOf(
                string("title", allowBlank = true), string("subtitle", allowBlank = true),
                string("title-key"), string("subtitle-key"),
                integer("fade-in", min = 0.0), integer("stay", min = 0.0), integer("fade-out", min = 0.0),
            ),
        ))
        catalog.register(descriptor(
            CapabilityKind.ACTION,
            "sound",
            parameters = listOf(
                string("sound", true, setOf("name")),
                decimal("volume", min = 0.0), decimal("pitch", min = 0.0, max = 2.0),
            ),
        ))
        catalog.register(descriptor(CapabilityKind.ACTION, "console_command", CapabilityRisk.HIGH, listOf(string("command", true))))
        catalog.register(descriptor(CapabilityKind.ACTION, "player_command", CapabilityRisk.MEDIUM, listOf(string("command", true))))
        catalog.register(descriptor(
            CapabilityKind.ACTION,
            "effect_apply",
            parameters = listOf(
                string("effect", true, setOf("effect-type")),
                enum("scope", setOf("while_inside", "while-inside", "timed", "until_mode_end", "until-mode-end")),
            ),
            strict = false,
        ))
        catalog.register(descriptor(CapabilityKind.ACTION, "effect_clear"))
        catalog.register(descriptor(CapabilityKind.ACTION, "teleport", CapabilityRisk.MEDIUM, listOf(string("location", true, setOf("value", "to")))))
        catalog.register(descriptor(CapabilityKind.ACTION, "heal", parameters = listOf(decimal("amount", min = 0.0))))
        catalog.register(descriptor(CapabilityKind.ACTION, "feed", parameters = listOf(integer("amount", min = 0.0, max = 20.0), decimal("saturation", min = 0.0, max = 20.0))))
        catalog.register(descriptor(CapabilityKind.ACTION, "extinguish"))
        catalog.register(descriptor(CapabilityKind.ACTION, "give_item", CapabilityRisk.MEDIUM, listOf(string("item", true, setOf("value")))))
        catalog.register(descriptor(CapabilityKind.ACTION, "take_item", CapabilityRisk.MEDIUM, listOf(string("item", true, setOf("value")))))
        catalog.register(descriptor(CapabilityKind.ACTION, "set_metadata", parameters = listOf(string("key", true), string("value"))))
        catalog.register(descriptor(CapabilityKind.ACTION, "clear_metadata", parameters = listOf(string("key", true))))
        catalog.register(descriptor(CapabilityKind.ACTION, "cleanup_region", risk = CapabilityRisk.MEDIUM))
        catalog.register(descriptor(
            CapabilityKind.ACTION,
            "mode_command",
            risk = CapabilityRisk.MEDIUM,
            parameters = listOf(enum("command", setOf("ready", "end", "stop"), required = true, aliases = setOf("value"))),
        ))
    }

    private fun registerConditions(catalog: CapabilityCatalog) {
        catalog.register(descriptor(CapabilityKind.CONDITION, "permission", parameters = listOf(string("value", true, setOf("node")))))
        catalog.register(descriptor(CapabilityKind.CONDITION, "region", parameters = listOf(string("value", true))))
        catalog.register(descriptor(CapabilityKind.CONDITION, "mode", parameters = listOf(string("value", true))))
        catalog.register(descriptor(CapabilityKind.CONDITION, "chance", parameters = listOf(decimal("value", true, 0.0, 100.0, setOf("percent")))))
        catalog.register(descriptor(CapabilityKind.CONDITION, "has_union"))
        catalog.register(descriptor(CapabilityKind.CONDITION, "union", parameters = listOf(string("value", true, setOf("id")))))
        catalog.register(descriptor(CapabilityKind.CONDITION, "metadata", parameters = listOf(string("key", true), string("value", true))))
        catalog.register(descriptor(CapabilityKind.CONDITION, "session_metadata", parameters = listOf(string("key", true), string("value", true))))
    }

    private fun descriptor(
        kind: CapabilityKind,
        id: String,
        risk: CapabilityRisk = CapabilityRisk.LOW,
        parameters: List<ParameterDescriptor> = emptyList(),
        requiredPlugins: Set<String> = emptySet(),
        strict: Boolean = true,
    ): CapabilityDescriptor = CapabilityDescriptor(kind, id, CapabilityStatus.STABLE, risk, parameters, requiredPlugins, strict)

    private fun string(
        key: String,
        required: Boolean = false,
        aliases: Set<String> = emptySet(),
        allowBlank: Boolean = false,
    ) = ParameterDescriptor(key, ParameterType.STRING, required, aliases, allowBlank = allowBlank)

    private fun integer(key: String, required: Boolean = false, min: Double? = null, max: Double? = null) =
        ParameterDescriptor(key, ParameterType.INTEGER, required, min = min, max = max)

    private fun decimal(
        key: String,
        required: Boolean = false,
        min: Double? = null,
        max: Double? = null,
        aliases: Set<String> = emptySet(),
    ) = ParameterDescriptor(key, ParameterType.DECIMAL, required, aliases, min = min, max = max)

    private fun bool(key: String, aliases: Set<String> = emptySet()) =
        ParameterDescriptor(key, ParameterType.BOOLEAN, aliases = aliases)

    private fun enum(
        key: String,
        values: Set<String>,
        required: Boolean = false,
        aliases: Set<String> = emptySet(),
    ) = ParameterDescriptor(key, ParameterType.ENUM, required, aliases, values)

}
