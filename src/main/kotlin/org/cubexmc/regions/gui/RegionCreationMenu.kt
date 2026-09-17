package org.cubexmc.regions.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.cubexmc.regions.model.ModeConfig
import org.cubexmc.regions.model.OwnerPolicy
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionSourceRef
import org.cubexmc.regions.service.RegionTemplate
import org.cubexmc.regions.service.templateDisplayName
import org.cubexmc.regions.service.templateDisplayDescription
import org.cubexmc.regions.service.TemplateParameter
import org.cubexmc.regions.service.TemplateParameterType
import java.util.Locale

/**
 * The creation wizard: pick an owned Lands area, then a template.
 *
 * Ownership is re-checked at every step, so an area that changed hands between opening the picker and
 * clicking it cannot be used.
 */
internal class RegionCreationMenu(private val gui: RegionsGui) {
    private val plugin get() = gui.plugin
    private val text get() = gui.text
    private val items get() = gui.items

    /** M2.2 阶段 1 的玩法卡位（与模式编辑页一致）。 */
    private val wizardModeSlots = linkedMapOf(
        10 to "free_event",
        11 to "run_race",
        12 to "dual_pvp",
        13 to "boat_race",
        14 to "union_war",
        15 to "horse_race",
        16 to "hide_and_seek",
        21 to "free_for_all",
    )

    /**
     * M2.2 阶段 1：先选玩法，再进入选地。向导进行中时选地后自动生成 ID 与默认名称，
     * 不再要求玩家手写 ASCII ID。
     */
    fun openModePicker(player: Player) {
        if (!gui.canEnterManagement(player)) return
        val inventory = Bukkit.createInventory(
            RegionsHolder(View.WIZARD_MODE),
            54,
            text.component(player, "gui.wizard.title"),
        )
        val unavailable = plugin.unions().active()?.type == "fallback"
        for ((slot, type) in wizardModeSlots) {
            val lore = text.lore(player, "gui.wizard.mode.$type.summary").toMutableList()
            if (type.equals("union_war", true) && unavailable) {
                lore += text.lore(player, "gui.wizard.mode.unavailable.reason")
            }
            val item = text.named(
                items.templateMaterial(type),
                text.text(player, "gui.wizard.mode.name", mapOf("mode" to text.label(player, "labels.mode." + type, type))),
                lore,
            )
            item.itemMeta?.let { meta ->
                meta.persistentDataContainer.set(gui.keys.modePicker, PersistentDataType.STRING, type)
                item.itemMeta = meta
            }
            inventory.setItem(slot, item)
        }
        inventory.setItem(49, items.back(player))
        player.openInventory(inventory)
    }

    fun clickModePicker(player: Player, slot: Int) {
        if (slot == 49) return gui.openMain(player)
        val inventory = player.openInventory.topInventory
        val item = inventory.getItem(slot) ?: return
        val type = item.itemMeta?.persistentDataContainer?.get(gui.keys.modePicker, PersistentDataType.STRING) ?: return
        gui.wizardDrafts.start(player.uniqueId, type)
        // 阶段 2：targetId/targetName 在选定地块时自动生成，占位值不参与展示。
        openOwnedAreas(player, OwnedAreaContext(OwnedAreaPurpose.CREATE, "-", "-"))
    }

    fun promptCreateRegion(player: Player) {
        gui.promptLine(player, "gui.prompt.create-id") { rawId ->
            val id = rawId.trim().lowercase(Locale.ROOT)
            if (!id.matches(REGION_ID)) {
                text.send(player, "gui.create.bad-id")
                gui.openMain(player)
                return@promptLine
            }
            if (gui.editable(id) != null) {
                text.send(player, "gui.create.exists", mapOf("id" to id))
                gui.openMain(player)
                return@promptLine
            }
            gui.promptLine(player, "gui.prompt.create-name") { rawName ->
                val name = rawName.trim().takeUnless { it == "-" || it.isBlank() } ?: id
                openOwnedAreas(player, OwnedAreaContext(OwnedAreaPurpose.CREATE, id, name))
            }
        }
    }

    fun openOwnedAreas(player: Player, context: OwnedAreaContext) {
        val source = plugin.sources().find("lands")
        if (source == null || !source.isAvailable()) {
            text.send(player, "gui.create.lands-unavailable")
            return if (context.purpose == OwnedAreaPurpose.BIND) {
                gui.openSource(player, context.targetId)
            } else {
                gui.openMain(player)
            }
        }
        val options = source.getOwnedRegions(player.uniqueId)
        val pageCount = ((options.size + GuiSlots.OWNED_AREA_PAGE_SIZE - 1) / GuiSlots.OWNED_AREA_PAGE_SIZE).coerceAtLeast(1)
        val page = context.page.coerceIn(0, pageCount - 1)
        val shownContext = context.copy(page = page)
        val holderRegion = context.targetId.takeIf { context.purpose == OwnedAreaPurpose.BIND }
        val inventory = Bukkit.createInventory(
            RegionsHolder(View.OWNED_AREAS, holderRegion, shownContext),
            54,
            text.component(player, "gui.area.title", mapOf("page" to (page + 1).toString(), "pages" to pageCount.toString())),
        )
        val shown = options.drop(page * GuiSlots.OWNED_AREA_PAGE_SIZE).take(GuiSlots.OWNED_AREA_PAGE_SIZE)
        for ((slot, option) in shown.withIndex()) {
            val land = option.values["land"] ?: continue
            val area = option.values["area"] ?: "default"
            val item = text.item(
                player,
                Material.GRASS_BLOCK,
                "gui.area.entry",
                mapOf("name" to option.name, "land" to land, "area" to area),
            )
            item.itemMeta?.let { meta ->
                meta.persistentDataContainer.set(gui.keys.land, PersistentDataType.STRING, land)
                meta.persistentDataContainer.set(gui.keys.area, PersistentDataType.STRING, area)
                item.itemMeta = meta
            }
            inventory.setItem(slot, item)
        }
        if (options.isEmpty()) {
            inventory.setItem(22, text.item(player, GuiIcons.EMPTY, "gui.area.empty"))
        }
        if (page > 0) inventory.setItem(45, text.named(Material.ARROW, text.text(player, "gui.common.previous-page")))
        inventory.setItem(
            49,
            text.item(
                player,
                Material.MAP,
                "gui.area.count",
                mapOf("count" to options.size.toString(), "page" to (page + 1).toString(), "pages" to pageCount.toString()),
            ),
        )
        if (page + 1 < pageCount) inventory.setItem(53, text.named(Material.ARROW, text.text(player, "gui.common.next-page")))
        inventory.setItem(50, items.back(player))
        player.openInventory(inventory)
    }

    fun clickOwnedArea(player: Player, holder: RegionsHolder, item: ItemStack?, slot: Int) {
        val context = holder.ownedArea ?: return gui.openMain(player)
        when (slot) {
            45 -> return openOwnedAreas(player, context.copy(page = context.page - 1))
            53 -> return openOwnedAreas(player, context.copy(page = context.page + 1))
            50 -> return if (context.purpose == OwnedAreaPurpose.BIND) {
                gui.openSource(player, context.targetId)
            } else {
                gui.openMain(player)
            }
        }
        if (slot !in 0 until GuiSlots.OWNED_AREA_PAGE_SIZE) return
        val meta = item?.itemMeta ?: return
        val land = meta.persistentDataContainer.get(gui.keys.land, PersistentDataType.STRING) ?: return
        val area = meta.persistentDataContainer.get(gui.keys.area, PersistentDataType.STRING) ?: return
        val ref = RegionSourceRef("lands", linkedMapOf("land" to land, "area" to area))
        if (!gui.allow(player, plugin.authority().canCreate(player, ref))) {
            text.send(player, "gui.area.owner-changed")
            return openOwnedAreas(player, context)
        }
        when (context.purpose) {
            OwnedAreaPurpose.CREATE -> {
                val wizard = gui.wizardDrafts.get(player.uniqueId)
                val targetId = if (wizard != null) {
                    AutoRegionId.generate(plugin.regions().all().mapTo(HashSet()) { it.id })
                } else {
                    context.targetId
                }
                val targetName = if (wizard != null) {
                    // 默认名称会写进 regions.yml、被所有管理员看到，所以用**服务器语言**生成：
                    // 否则同一块地会因为谁先创建而带上不同语言的默认名（PLAN.md §4.2 的稳定数据）。
                    val modeLabel = plugin.lang().label("labels.mode." + wizard.modeType, wizard.modeType)
                    "$modeLabel · $land/$area"
                } else {
                    context.targetName
                }
                if (gui.editable(targetId) != null) {
                    text.send(player, "gui.create.exists", mapOf("id" to targetId))
                    return gui.openMain(player)
                }
                openTemplates(player, TemplateContext(targetId, targetName, ref))
            }
            OwnedAreaPurpose.BIND -> {
                val region = gui.editable(context.targetId) ?: return gui.openMain(player)
                gui.saveAndReopen(player, region.copy(source = ref, ownerPolicy = OwnerPolicy.LANDS_OWNER)) {
                    gui.openSource(player, region.id)
                }
            }
        }
    }

    fun openTemplatesForRegion(player: Player, regionId: String) {
        val region = gui.editable(regionId) ?: return gui.openMain(player)
        if (!gui.canManageRegion(player, region)) return
        openTemplates(
            player,
            TemplateContext(region.id, region.name, region.source, purpose = TemplatePurpose.APPLY),
        )
    }

    private fun openTemplates(player: Player, context: TemplateContext) {
        val currentContext = if (context.purpose == TemplatePurpose.APPLY) {
            val region = gui.editable(context.targetId) ?: return gui.openMain(player)
            if (!gui.canManageRegion(player, region)) return
            context.copy(targetName = region.name, source = region.source)
        } else {
            context
        }
        val wizard = gui.wizardDrafts.get(player.uniqueId)
        val allTemplates = plugin.templates().all()
        val templates = WizardTemplates.matching(allTemplates, wizard?.modeType, context.purpose)
        if (wizard != null && context.purpose == TemplatePurpose.CREATE && templates.isEmpty()) {
            // 这个玩法一个模板都没有（升级安装没拿到新内置模板、或服主删过模板）：
            // 按玩法默认值直接建草稿并进入阶段 3，不能把玩家卡在空列表上。
            return createFromModeDefaults(player, context, wizard.modeType)
        }
        val pageCount = ((templates.size + GuiSlots.TEMPLATE_PAGE_SIZE - 1) / GuiSlots.TEMPLATE_PAGE_SIZE).coerceAtLeast(1)
        val page = currentContext.page.coerceIn(0, pageCount - 1)
        val shownContext = currentContext.copy(page = page)
        val holderRegion = currentContext.targetId.takeIf { currentContext.purpose == TemplatePurpose.APPLY }
        val inventory = Bukkit.createInventory(
            RegionsHolder(View.TEMPLATES, holderRegion, template = shownContext),
            54,
            text.component(player, "gui.template.title", mapOf("page" to (page + 1).toString(), "pages" to pageCount.toString())),
        )
        val none = text.text(player, "gui.common.none")
        val shown = templates.drop(page * GuiSlots.TEMPLATE_PAGE_SIZE).take(GuiSlots.TEMPLATE_PAGE_SIZE)
        for ((slot, template) in shown.withIndex()) {
            val lore = text.lore(
                player,
                "gui.template.entry.lore",
                mapOf(
                    "description" to plugin.templateDisplayDescription(template, player),
                    "mode" to (template.mode?.type ?: none),
                    "flags" to template.flags.keys.joinToString(", ").ifBlank { none },
                    "effects" to template.effects.joinToString(", ") { it.type }.ifBlank { none },
                    "triggers" to template.triggers.values.sumOf { it.size }.toString(),
                ),
            ) + if (template.parameters.isNotEmpty()) {
                listOf(text.text(player, "gui.template.parameters", mapOf("keys" to template.parameters.keys.joinToString(", "))))
            } else {
                emptyList()
            } + text.text(
                player,
                if (currentContext.purpose == TemplatePurpose.APPLY) {
                    "gui.template.entry.apply-hint"
                } else {
                    "gui.template.entry.create-hint"
                },
            )
            val item = text.named(
                items.templateMaterial(template.mode?.type),
                text.text(player, "gui.template.entry.name", mapOf("name" to plugin.templateDisplayName(template, player))),
                lore,
            )
            item.itemMeta?.let { meta ->
                meta.persistentDataContainer.set(gui.keys.template, PersistentDataType.STRING, template.id)
                item.itemMeta = meta
            }
            inventory.setItem(slot, item)
        }
        if (templates.isEmpty()) {
            inventory.setItem(22, text.item(player, GuiIcons.EMPTY, "gui.template.empty"))
        }
        if (page > 0) inventory.setItem(45, text.named(Material.ARROW, text.text(player, "gui.common.previous-page")))
        inventory.setItem(
            49,
            text.item(
                player,
                Material.BOOK,
                "gui.template.count",
                mapOf("count" to templates.size.toString(), "page" to (page + 1).toString(), "pages" to pageCount.toString()),
            ),
        )
        inventory.setItem(
            50,
            text.named(
                GuiIcons.BACK,
                text.text(
                    player,
                    if (currentContext.purpose == TemplatePurpose.APPLY) {
                        "gui.template.back-to-detail"
                    } else {
                        "gui.template.back-to-areas"
                    },
                ),
            ),
        )
        if (page + 1 < pageCount) inventory.setItem(53, text.named(Material.ARROW, text.text(player, "gui.common.next-page")))
        player.openInventory(inventory)
    }

    fun clickTemplate(player: Player, holder: RegionsHolder, item: ItemStack?, slot: Int) {
        val context = holder.template ?: return gui.openMain(player)
        when (slot) {
            45 -> return openTemplates(player, context.copy(page = context.page - 1))
            53 -> return openTemplates(player, context.copy(page = context.page + 1))
            50 -> return if (context.purpose == TemplatePurpose.APPLY) {
                gui.openDetail(player, context.targetId)
            } else {
                openOwnedAreas(
                    player,
                    OwnedAreaContext(OwnedAreaPurpose.CREATE, context.targetId, context.targetName),
                )
            }
        }
        if (slot !in 0 until GuiSlots.TEMPLATE_PAGE_SIZE) return
        val templateId = item?.itemMeta?.persistentDataContainer
            ?.get(gui.keys.template, PersistentDataType.STRING) ?: return
        if (context.purpose == TemplatePurpose.CREATE) {
            if (!gui.allow(player, plugin.authority().canCreate(player, context.source))) {
                text.send(player, "gui.template.owner-changed")
                return openOwnedAreas(
                    player,
                    OwnedAreaContext(OwnedAreaPurpose.CREATE, context.targetId, context.targetName),
                )
            }
            if (gui.editable(context.targetId) != null) {
                text.send(player, "gui.create.exists", mapOf("id" to context.targetId))
                return gui.openMain(player)
            }
        } else {
            val region = gui.editable(context.targetId) ?: return gui.openMain(player)
            if (!gui.canManageRegion(player, region)) return
        }
        val template = plugin.templates().find(templateId)
        if (template == null) {
            text.send(player, "gui.template.failed", mapOf("errors" to templateId))
            return openTemplates(player, context)
        }
        collectParameters(player, context, template, template.parameters.values.toList(), emptyMap())
    }

    /**
     * 逐个把模板声明的参数问出来，凑齐了再套用。
     *
     * 模板不声明参数时（多数模板如此）直接套用，流程和以前一样。声明了参数的模板不再随包硬编码
     * 一个占位值——像复活点那种只校验格式不校验世界是否存在的字段，硬编码会一路通过校验，
     * 直到玩家死在场地里才发现坐标是错的。
     */
    private fun collectParameters(
        player: Player,
        context: TemplateContext,
        template: RegionTemplate,
        pending: List<TemplateParameter>,
        collected: Map<String, String>,
    ) {
        val parameter = pending.firstOrNull() ?: return finishTemplateSelection(player, context, template, collected)
        val promptKey = if (parameter.type == TemplateParameterType.LOCATION) {
            "gui.prompt.template-location"
        } else {
            "gui.prompt.template-value"
        }
        gui.promptLine(player, promptKey, mapOf("parameter" to parameter.id)) { raw ->
            val value = resolveHereKeyword(player, parameter, raw)
            val error = parameter.validate(value)
            if (error != null) {
                text.send(player, "gui.template.parameter-invalid", mapOf("reason" to error))
                return@promptLine collectParameters(player, context, template, pending, collected)
            }
            collectParameters(player, context, template, pending.drop(1), collected + (parameter.id to value))
        }
    }

    /** 位置参数支持一个 `here`，直接取玩家脚下的坐标，省得对着屏幕手抄 world,x,y,z。 */
    private fun resolveHereKeyword(player: Player, parameter: TemplateParameter, raw: String): String {
        val trimmed = raw.trim()
        if (parameter.type != TemplateParameterType.LOCATION) return trimmed
        val here = trimmed.equals("here", ignoreCase = true) ||
            trimmed.equals(text.text(player, "gui.prompt.here-word"), ignoreCase = true)
        if (!here) return trimmed
        val location = player.location
        return "${location.world?.name},${location.blockX},${location.blockY},${location.blockZ}"
    }

    private fun finishTemplateSelection(
        player: Player,
        context: TemplateContext,
        template: RegionTemplate,
        supplied: Map<String, String>,
    ) {
        if (context.purpose == TemplatePurpose.APPLY) {
            return openTemplateConfirmation(player, context, template, supplied)
        }
        // 问参数期间玩家可能已经被撤权，或者这块地被别人先绑走了——写之前重新核一次。
        if (!gui.allow(player, plugin.authority().canCreate(player, context.source))) {
            text.send(player, "gui.template.owner-changed")
            return gui.openMain(player)
        }
        if (gui.editable(context.targetId) != null) {
            text.send(player, "gui.create.exists", mapOf("id" to context.targetId))
            return gui.openMain(player)
        }
        val base = RegionDefinition(
            id = context.targetId,
            name = context.targetName,
            source = context.source,
            ownerPolicy = OwnerPolicy.LANDS_OWNER,
            mode = ModeConfig("free_event"),
        )
        val applied = plugin.templates().apply(template.id, base, supplied)
        val region = applied.region
        if (!applied.success || region == null) {
            text.send(player, "gui.template.failed", mapOf("errors" to applied.errors.joinToString("; ")))
            return openTemplates(player, context)
        }
        gui.saveAndReopen(player, region) {
            // 向导创建：记住草稿与版本，直接进入阶段 3"设置必要项目"（PLAN.md §5.2）。
            val created = gui.editable(region.id)
            val wizard = gui.wizardDrafts.get(player.uniqueId)
            if (created != null && wizard != null) {
                gui.wizardDrafts.enterSettings(player.uniqueId, created.id, created.revision)
                openWizardSettings(player, created.id, backToMode = true)
            } else {
                gui.openDetail(player, region.id)
            }
        }
    }

/**
     * 阶段 3「只展示当前玩法必填项」（PLAN.md §5.2）。
     *
     * 页面只画当前玩法真正需要的格子：返回/观战点、出生点、装备预设、人数与时限；
     * free_event 这类没有必填项的玩法直接提示可以发布。顶部一行如实写出还缺哪些必填项，
     * 不去猜、也不把错误藏到发布页。
     */
    fun openWizardSettings(player: Player, regionId: String, backToMode: Boolean = false) {
        val region = gui.editable(regionId) ?: return gui.openMain(player)
        if (!gui.canManageRegion(player, region)) return
        val values = region.mode?.values.orEmpty()
        val here = GuiValues.formatLocation(player.location)
        val required = WizardRequiredFields.of(region)
        val inventory = Bukkit.createInventory(
            RegionsHolder(View.WIZARD_SETTINGS, region.id, wizardBackToMode = backToMode),
            54,
            text.component(player, "gui.wizard.settings.title", mapOf("name" to region.name)),
        )
        inventory.setItem(4, items.region(player, region))
        inventory.setItem(
            8,
            text.named(
                if (required.missing.isEmpty()) Material.LIME_DYE else Material.RED_DYE,
                text.text(
                    player,
                    "gui.wizard.settings.progress",
                    mapOf(
                        "done" to (required.fields.size - required.missing.size).toString(),
                        "total" to required.fields.size.toString(),
                        "missing" to required.missing.joinToString(", ") { text.text(player, it.key) },
                    ),
                ),
                text.lore(player, if (required.missing.isEmpty()) "gui.wizard.settings.ready" else "gui.wizard.settings.not-ready"),
            ),
        )
        // 通用两项：返回点（战斗玩法的出场/观战点）与名称。
        if (required.fields.contains(WizardField.RESPAWN)) {
            inventory.setItem(
                19,
                text.item(player, Material.LODESTONE, "gui.wizard.settings.respawn", mapOf("value" to (values["respawn"] ?: text.text(player, "gui.common.unset")), "location" to here)),
            )
        }
        if (required.fields.contains(WizardField.SPAWNS)) {
            inventory.setItem(
                20,
                text.item(
                    player,
                    Material.RESPAWN_ANCHOR,
                    "gui.wizard.settings.spawn",
                    mapOf("count" to org.cubexmc.regions.match.MatchSpawns.parseList(values["spawn-points"]).size.toString(), "location" to here),
                ),
            )
        }
        if (required.fields.contains(WizardField.TEAM_SPAWNS)) {
            inventory.setItem(
                21,
                text.item(
                    player,
                    Material.RED_BED,
                    "gui.wizard.settings.spawn-b",
                    mapOf("count" to org.cubexmc.regions.match.MatchSpawns.parseList(values["spawn-points-b"]).size.toString(), "location" to here),
                ),
            )
        }
        if (required.fields.contains(WizardField.KIT)) {
            inventory.setItem(28, text.item(player, Material.IRON_SWORD, "gui.mode.kit-iron"))
            inventory.setItem(29, text.item(player, Material.BOW, "gui.mode.kit-bow"))
            inventory.setItem(30, text.item(player, Material.DIAMOND_SWORD, "gui.mode.kit-diamond"))
            inventory.setItem(31, text.item(player, GuiIcons.CLEAR, "gui.mode.kit-clear"))
        }
        if (required.fields.contains(WizardField.ROSTER)) {
            inventory.setItem(33, text.item(player, Material.PLAYER_HEAD, "gui.wizard.settings.roster", mapOf("value" to rosterSummary(region))))
        }
        if (required.fields.contains(WizardField.TIME)) {
            inventory.setItem(34, text.item(player, Material.CLOCK, "gui.wizard.settings.time", mapOf("value" to timeSummary(region))))
        }
        inventory.setItem(40, text.item(player, Material.EMERALD_BLOCK, "gui.wizard.settings.continue"))
        inventory.setItem(42, text.item(player, Material.NAME_TAG, "gui.wizard.settings.rename"))
        inventory.setItem(45, text.item(player, GuiIcons.BACK, if (backToMode) "gui.wizard.back-to-mode" else "gui.wizard.back-to-area"))
        player.openInventory(inventory)
    }

    fun clickWizardSettings(player: Player, holder: RegionsHolder, slot: Int) {
        val regionId = holder.regionId ?: return gui.openMain(player)
        val region = gui.editable(regionId) ?: return gui.openMain(player)
        if (!gui.canManageRegion(player, region)) return
        val wizard = gui.wizardDrafts.get(player.uniqueId)
        when (slot) {
            45 -> if (holder.wizardBackToMode) openModePicker(player) else openOwnedAreas(
                player,
                OwnedAreaContext(OwnedAreaPurpose.CREATE, region.id, region.name),
            )

            42 -> gui.promptLine(player, "gui.prompt.rename") { raw ->
                val name = raw.trim()
                if (name.isBlank()) {
                    text.send(player, "gui.create.bad-name")
                    openWizardSettings(player, regionId, holder.wizardBackToMode)
                    return@promptLine
                }
                saveWizardValues(player, region, region.copy(name = name), holder.wizardBackToMode)
            }

            19 -> saveWizardValues(player, region, withModeValue(region, "respawn", GuiValues.formatLocation(player.location)), holder.wizardBackToMode)
            20 -> saveWizardValues(player, region, appendSpawnValue(region, "spawn-points", player), holder.wizardBackToMode)
            21 -> saveWizardValues(player, region, appendSpawnValue(region, "spawn-points-b", player), holder.wizardBackToMode)
            28, 29, 30 -> saveWizardValues(player, region, withKit(region, GuiValues.COMBAT_KITS.getValue(slot)), holder.wizardBackToMode)
            31 -> saveWizardValues(player, region, withKit(region, emptyMap()), holder.wizardBackToMode)
            33 -> saveWizardValues(player, region, adjustRoster(region), holder.wizardBackToMode)
            34 -> saveWizardValues(player, region, adjustTime(region), holder.wizardBackToMode)
            40 -> {
                // 必填项没齐就不放行到发布页，并指出缺哪一项（PLAN.md §5.2 阶段 4 前置）。
                val required = WizardRequiredFields.of(region)
                if (required.missing.isNotEmpty()) {
                    text.send(
                        player,
                        "gui.wizard.settings.blocked",
                        mapOf("missing" to required.missing.joinToString(", ") { text.text(player, it.key) }),
                    )
                    openWizardSettings(player, regionId, holder.wizardBackToMode)
                    return
                }
                wizard?.let { gui.wizardDrafts.syncRevision(player.uniqueId, it.revision) }
                gui.openPublishPreview(player, regionId)
            }
        }
    }

    /** 阶段 3 的每次修改都带上向导记录的期望 revision，别人改过草稿就拒绝写入。 */
    private fun saveWizardValues(
        player: Player,
        region: RegionDefinition,
        updated: RegionDefinition,
        backToMode: Boolean,
    ) {
        val expected = gui.wizardDrafts.get(player.uniqueId)?.revision
        if (expected != null && expected != updated.revision) {
            text.send(player, "gui.wizard.settings.stale")
            openWizardSettings(player, region.id, backToMode)
            return
        }
        val result = plugin.publishing().saveDraft(player, updated, expectedRevision = expected)
        if (!result.success) {
            text.send(player, "gui.save.failed", mapOf("reason" to text.resultReason(player, result.code, result.args, result.reason)))
            return
        }
        plugin.publishing().draft(region.id)?.let { gui.wizardDrafts.syncRevision(player.uniqueId, it.revision) }
        openWizardSettings(player, region.id, backToMode)
    }

    private fun withModeValue(region: RegionDefinition, key: String, value: String): RegionDefinition {
        val mode = region.mode ?: ModeConfig("free_event")
        return region.copy(mode = mode.copy(values = LinkedHashMap(mode.values).apply { this[key] = value }))
    }

    private fun appendSpawnValue(region: RegionDefinition, key: String, player: Player): RegionDefinition {
        val mode = region.mode ?: ModeConfig("free_event")
        val values = LinkedHashMap(mode.values)
        val here = GuiValues.formatLocation(player.location)
        val existing = values[key].orEmpty()
        values[key] = if (existing.isBlank()) here else "$existing;$here"
        return region.copy(mode = mode.copy(values = values))
    }

    private fun withKit(region: RegionDefinition, kit: Map<String, String>): RegionDefinition {
        val mode = region.mode ?: ModeConfig("free_event")
        val values = LinkedHashMap(mode.values)
        for (key in listOf("kit", "armor", "offhand")) values.remove(key)
        values.putAll(kit)
        return region.copy(mode = mode.copy(values = values))
    }

    /** 人数：大乱斗/决斗调最低人数，工会战调每队人数（服务端按 2–10 归一）。 */
    private fun adjustRoster(region: RegionDefinition): RegionDefinition {
        val mode = region.mode ?: return region
        val values = LinkedHashMap(mode.values)
        when (mode.type.lowercase(Locale.ROOT)) {
            "union_war" -> {
                val next = ((values["team-size"]?.toIntOrNull() ?: 5) % 10) + 1
                values["team-size"] = next.coerceIn(2, 10).toString()
            }

            "dual_pvp" -> Unit
            else -> {
                val next = ((values["min-players"]?.toIntOrNull() ?: 4) % 16) + 1
                values["min-players"] = next.coerceAtLeast(2).toString()
            }
        }
        return region.copy(mode = mode.copy(values = values))
    }

    /** 时限：决斗是回合时长，其余是整场时限；点击循环 120/180/300/600 秒。 */
    private fun adjustTime(region: RegionDefinition): RegionDefinition {
        val mode = region.mode ?: return region
        val key = if (mode.type.equals("dual_pvp", ignoreCase = true)) "round-seconds" else "timeout-seconds"
        val values = LinkedHashMap(mode.values)
        val options = listOf(120, 180, 300, 600)
        val current = values[key]?.toIntOrNull() ?: options.first()
        val next = options[(options.indexOf(current).takeIf { it >= 0 } ?: 0).let { (it + 1) % options.size }]
        values[key] = next.toString()
        return region.copy(mode = mode.copy(values = values))
    }

    private fun rosterSummary(region: RegionDefinition): String {
        val values = region.mode?.values.orEmpty()
        return when (region.mode?.type?.lowercase(Locale.ROOT)) {
            "union_war" -> "team-size=${values["team-size"] ?: "5"}"
            "dual_pvp" -> "2"
            else -> "min=${values["min-players"] ?: "4"}, max=${values["max-players"] ?: "16"}"
        }
    }

    private fun timeSummary(region: RegionDefinition): String {
        val values = region.mode?.values.orEmpty()
        val key = if (region.mode?.type.equals("dual_pvp", ignoreCase = true)) "round-seconds" else "timeout-seconds"
        return "${values[key] ?: "600"}s"
    }

    /** 阶段 3 的保存入口也用乐观并发：只在持有向导草稿时启用。 */
    private fun wizardExpectedRevision(player: Player): Long? = gui.wizardDrafts.get(player.uniqueId)?.revision


/** 没有匹配模板时按玩法默认值建草稿，并进入阶段 3（PLAN.md §5.2 阶段 2→3）。 */
    private fun createFromModeDefaults(
        player: Player,
        context: TemplateContext,
        modeType: String,
    ) {
        if (!gui.allow(player, plugin.authority().canCreate(player, context.source))) {
            text.send(player, "gui.template.owner-changed")
            return gui.openMain(player)
        }
        if (gui.editable(context.targetId) != null) {
            text.send(player, "gui.create.exists", mapOf("id" to context.targetId))
            return gui.openMain(player)
        }
        val region = RegionDefinition(
            id = context.targetId,
            name = context.targetName,
            source = context.source,
            ownerPolicy = OwnerPolicy.LANDS_OWNER,
            mode = ModeConfig(modeType, GuiValues.defaultModeValues(modeType)),
        )
        text.send(player, "gui.wizard.no-template", mapOf("mode" to plugin.lang().label("labels.mode.$modeType", modeType)))
        gui.saveAndReopen(player, region) {
            val created = gui.editable(region.id)
            if (created != null) {
                gui.wizardDrafts.enterSettings(player.uniqueId, created.id, created.revision)
                openWizardSettings(player, created.id, backToMode = true)
            } else {
                gui.openDetail(player, region.id)
            }
        }
    }

    private fun openTemplateConfirmation(
        player: Player,
        context: TemplateContext,
        template: RegionTemplate,
        supplied: Map<String, String>,
    ) {
        val current = gui.editable(context.targetId) ?: return gui.openMain(player)
        if (!gui.canManageRegion(player, current)) return
        val applied = plugin.templates().apply(template.id, current, supplied)
        val candidate = applied.region
        if (!applied.success || candidate == null) {
            text.send(player, "gui.template.failed", mapOf("errors" to applied.errors.joinToString("; ")))
            return openTemplates(player, context)
        }
        val inventory = Bukkit.createInventory(
            RegionsHolder(
                View.TEMPLATE_CONFIRM,
                current.id,
                template = context,
                templateConfirmation = TemplateConfirmation(template.id, supplied),
            ),
            27,
            text.component(player, "gui.template.confirm.title", mapOf("id" to current.id)),
        )
        inventory.setItem(4, items.region(player, current))
        inventory.setItem(
            11,
            text.item(
                player,
                items.templateMaterial(candidate.mode?.type),
                "gui.template.confirm.summary",
                mapOf(
                    "name" to plugin.templateDisplayName(template, player),
                    "mode" to (candidate.mode?.type ?: text.text(player, "gui.common.none")),
                    "flags" to candidate.flags.size.toString(),
                    "effects" to candidate.effects.size.toString(),
                    "triggers" to candidate.triggers.values.sumOf { it.size }.toString(),
                ),
            ),
        )
        inventory.setItem(13, text.item(player, Material.REDSTONE_BLOCK, "gui.template.confirm.warning"))
        inventory.setItem(15, text.item(player, Material.LIME_CONCRETE, "gui.template.confirm.apply"))
        inventory.setItem(22, items.back(player))
        player.openInventory(inventory)
    }

    fun clickTemplateConfirmation(player: Player, holder: RegionsHolder, slot: Int) {
        val context = holder.template ?: return gui.openMain(player)
        if (slot == 22) return openTemplates(player, context)
        if (slot != 15) return
        val confirmation = holder.templateConfirmation ?: return openTemplates(player, context)
        val current = gui.editable(context.targetId) ?: return gui.openMain(player)
        if (!gui.canManageRegion(player, current)) return
        val applied = plugin.templates().apply(confirmation.templateId, current, confirmation.supplied)
        val region = applied.region
        if (!applied.success || region == null) {
            text.send(player, "gui.template.failed", mapOf("errors" to applied.errors.joinToString("; ")))
            return openTemplates(player, context)
        }
        gui.wizardDrafts.clear(player.uniqueId)
        gui.saveAndReopen(player, region) { gui.openDetail(player, region.id) }
    }

    private companion object {
        val REGION_ID = Regex("[a-z0-9_-]{2,48}")
    }
}

/** 阶段 3 会展示的必填项类别。 */
internal enum class WizardField(val key: String) {
    RESPAWN("gui.wizard.settings.field.respawn"),
    SPAWNS("gui.wizard.settings.field.spawns"),
    TEAM_SPAWNS("gui.wizard.settings.field.team-spawns"),
    KIT("gui.wizard.settings.field.kit"),
    ROSTER("gui.wizard.settings.field.roster"),
    TIME("gui.wizard.settings.field.time"),
}

/**
 * 当前玩法真正需要的必填项，以及还缺哪些（PLAN.md §5.2 阶段 3："只展示当前玩法必填项"）。
 * 纯函数：测得到、也不依赖 Bukkit。
 */
internal object WizardRequiredFields {

    data class Required(val fields: List<WizardField>, val missing: List<WizardField>)

    fun of(region: RegionDefinition): Required {
        val values = region.mode?.values.orEmpty()
        val fields = when (region.mode?.type?.lowercase(Locale.ROOT)) {
            "dual_pvp" -> listOf(WizardField.RESPAWN, WizardField.SPAWNS, WizardField.KIT, WizardField.TIME)
            "union_war" -> listOf(WizardField.RESPAWN, WizardField.SPAWNS, WizardField.TEAM_SPAWNS, WizardField.KIT, WizardField.ROSTER, WizardField.TIME)
            "free_for_all" -> listOf(WizardField.RESPAWN, WizardField.SPAWNS, WizardField.KIT, WizardField.ROSTER, WizardField.TIME)
            "hide_and_seek" -> listOf(WizardField.RESPAWN, WizardField.TIME)
            "run_race", "boat_race", "horse_race" -> listOf(WizardField.TIME)
            else -> emptyList()
        }
        val missing = fields.filter { field ->
            when (field) {
                WizardField.RESPAWN -> values["respawn"].isNullOrBlank() && values["outside"].isNullOrBlank()
                WizardField.SPAWNS -> org.cubexmc.regions.match.MatchSpawns.parseList(values["spawn-points"]).isEmpty()
                WizardField.TEAM_SPAWNS -> org.cubexmc.regions.match.MatchSpawns.parseList(values["spawn-points-b"]).isEmpty()
                WizardField.KIT -> values["kit"].isNullOrBlank() && values["armor"].isNullOrBlank()
                WizardField.ROSTER -> false
                WizardField.TIME -> false
            }
        }
        return Required(fields, missing)
    }
}
