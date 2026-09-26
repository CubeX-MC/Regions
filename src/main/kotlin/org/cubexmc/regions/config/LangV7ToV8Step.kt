package org.cubexmc.regions.config

/**
 * lang 7 → 8：把**缺少的叶子键按同语言内置文本补齐**。
 *
 * v7 之后语言文件新增了大量内容：`labels.*`（能力／状态／字段／严重级译名）、`errors.*`
 * （稳定错误码文案）、`gui.game.*`、`gui.wizard.settings.*`、`game.match.*`、
 * `labels.participant/outcome/reward` 等。既有安装的文件版本号已经是 7，迁移框架据此认为
 * "无需处理"，于是这些键在真机上解析不出来——`labels.state.idle` 会原样显示成键名，
 * `errors.*` 退化成英文诊断句（2026-09-13 实服验证发现）。
 *
 * 补键规则见 [FillMissingLanguageKeysStep]。
 */
internal class LangV7ToV8Step(locale: String) : FillMissingLanguageKeysStep(locale, 7, 8)
