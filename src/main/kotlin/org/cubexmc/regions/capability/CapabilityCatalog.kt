package org.cubexmc.regions.capability

import java.util.Locale

class CapabilityCatalog {
    private val descriptors: MutableMap<Pair<CapabilityKind, String>, CapabilityDescriptor> = LinkedHashMap()

    fun register(descriptor: CapabilityDescriptor) {
        val normalized = descriptor.id.lowercase(Locale.ROOT)
        require(normalized.isNotBlank()) { "Capability id cannot be blank." }
        val key = descriptor.kind to normalized
        require(!descriptors.containsKey(key)) { "Capability ${descriptor.kind}:$normalized is already registered." }
        descriptors[key] = descriptor.copy(id = normalized)
    }

    fun find(kind: CapabilityKind, id: String): CapabilityDescriptor? =
        descriptors[kind to id.lowercase(Locale.ROOT)]

    fun all(kind: CapabilityKind? = null): List<CapabilityDescriptor> =
        descriptors.values.filter { kind == null || it.kind == kind }

    fun stableIds(kind: CapabilityKind): Set<String> =
        all(kind).filter { it.status == CapabilityStatus.STABLE }.mapTo(LinkedHashSet()) { it.id }

    fun validate(kind: CapabilityKind, id: String, values: Map<String, String>): List<CapabilityValidationIssue> {
        val descriptor = find(kind, id)
            ?: return listOf(CapabilityValidationIssue(
                code = "capability-unknown",
                args = mapOf("kind" to kind.name.lowercase(Locale.ROOT), "id" to id),
                message = "Unknown $kind capability '$id'.",
            ))
        if (descriptor.status != CapabilityStatus.STABLE) {
            return listOf(CapabilityValidationIssue(
                code = "capability-not-stable",
                args = mapOf(
                    "kind" to descriptor.kind.name.lowercase(Locale.ROOT),
                    "id" to descriptor.id,
                    "status" to descriptor.status.name.lowercase(Locale.ROOT),
                ),
                message = "Capability ${descriptor.kind}:${descriptor.id} is ${descriptor.status}.",
            ))
        }
        val normalizedValues = values.mapKeys { it.key.lowercase(Locale.ROOT) }
        val issues = ArrayList<CapabilityValidationIssue>()
        for (parameter in descriptor.parameters) {
            val lookupKeys = listOf(parameter.key.lowercase(Locale.ROOT)) +
                parameter.aliases.map { it.lowercase(Locale.ROOT) }
            val entry = lookupKeys.firstNotNullOfOrNull { key ->
                normalizedValues[key]?.let { key to it }
            }
            if (entry == null) {
                if (parameter.required) {
                    issues.add(capabilityParameterIssue(descriptor, parameter, "parameter-required", "requires"))
                }
                continue
            }
            validateValue(descriptor, parameter, entry.second)?.let { issues.add(it) }
        }
        if (descriptor.strictParameters) {
            val accepted = descriptor.parameters.flatMapTo(HashSet()) { it.acceptedKeys() }
            for (key in normalizedValues.keys) {
                if (!accepted.contains(key)) {
                    issues.add(CapabilityValidationIssue(
                        code = "parameter-unknown",
                        args = mapOf("capability" to capabilityLabel(descriptor), "parameter" to key),
                        fieldPath = key,
                        message = "${descriptor.kind}:${descriptor.id} does not support parameter '$key'.",
                    ))
                }
            }
        }
        return issues
    }

    private fun capabilityLabel(descriptor: CapabilityDescriptor): String =
        "${descriptor.kind.name.lowercase(Locale.ROOT)}:${descriptor.id}"

    private fun capabilityParameterIssue(
        descriptor: CapabilityDescriptor,
        parameter: ParameterDescriptor,
        code: String,
        diagnostic: String,
    ): CapabilityValidationIssue = CapabilityValidationIssue(
        code = code,
        args = mapOf("capability" to capabilityLabel(descriptor), "parameter" to parameter.key),
        fieldPath = parameter.key,
        message = "${descriptor.kind}:${descriptor.id} parameter '${parameter.key}' $diagnostic.",
    )

    private fun validateValue(
        descriptor: CapabilityDescriptor,
        parameter: ParameterDescriptor,
        raw: String,
    ): CapabilityValidationIssue? {
        if (raw.isBlank() && !parameter.allowBlank) {
            return if (parameter.required) {
                capabilityParameterIssue(descriptor, parameter, "parameter-blank", "cannot be blank")
            } else {
                null
            }
        }
        val number = when (parameter.type) {
            ParameterType.INTEGER -> raw.toLongOrNull()?.toDouble()
            ParameterType.DECIMAL -> raw.toDoubleOrNull()
            else -> null
        }
        when (parameter.type) {
            ParameterType.INTEGER -> if (number == null) {
                return capabilityParameterIssue(descriptor, parameter, "parameter-integer", "must be an integer")
            }
            ParameterType.DECIMAL -> if (number == null || !number.isFinite()) {
                return capabilityParameterIssue(descriptor, parameter, "parameter-number", "must be a finite number")
            }
            ParameterType.BOOLEAN -> if (raw.toBooleanStrictOrNull() == null) {
                return capabilityParameterIssue(descriptor, parameter, "parameter-boolean", "must be true or false")
            }
            ParameterType.ENUM -> if (parameter.allowedValues.none { it.equals(raw, ignoreCase = true) }) {
                return CapabilityValidationIssue(
                    code = "parameter-enum",
                    args = mapOf(
                        "capability" to capabilityLabel(descriptor),
                        "parameter" to parameter.key,
                        "values" to parameter.allowedValues.joinToString(", "),
                    ),
                    fieldPath = parameter.key,
                    message = "${descriptor.kind}:${descriptor.id} parameter '${parameter.key}' " +
                        "must be one of ${parameter.allowedValues.joinToString()}.",
                )
            }
            ParameterType.STRING -> Unit
        }
        if (number != null && parameter.min != null && number < parameter.min) {
            return CapabilityValidationIssue(
                code = "parameter-min",
                args = mapOf(
                    "capability" to capabilityLabel(descriptor),
                    "parameter" to parameter.key,
                    "min" to parameter.min.toString(),
                ),
                fieldPath = parameter.key,
                message = "${descriptor.kind}:${descriptor.id} parameter '${parameter.key}' must be >= ${parameter.min}.",
            )
        }
        if (number != null && parameter.max != null && number > parameter.max) {
            return CapabilityValidationIssue(
                code = "parameter-max",
                args = mapOf(
                    "capability" to capabilityLabel(descriptor),
                    "parameter" to parameter.key,
                    "max" to parameter.max.toString(),
                ),
                fieldPath = parameter.key,
                message = "${descriptor.kind}:${descriptor.id} parameter '${parameter.key}' must be <= ${parameter.max}.",
            )
        }
        return null
    }
}
