package org.cubexmc.regions.service

/**
 * [reason] is the English diagnostic (or, for authority denials, a language key) kept for logs and
 * tests; [code] + [args] render from `errors.<code>` when present — see LanguageManager.resultReason.
 */
class ServiceResult private constructor(
    val success: Boolean,
    val reason: String = "",
    val code: String? = null,
    val args: Map<String, String> = emptyMap(),
) {
    companion object {
        fun ok(): ServiceResult = ServiceResult(true)

        fun fail(reason: String): ServiceResult = ServiceResult(false, reason)

        fun failCoded(code: String, args: Map<String, String> = emptyMap(), diagnostic: String = ""): ServiceResult =
            ServiceResult(false, diagnostic, code, args)
    }
}
