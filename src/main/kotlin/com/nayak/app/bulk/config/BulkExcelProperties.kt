package com.nayak.app.bulk.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.validation.annotation.Validated

enum class HeaderMode { SHORT, DOT }

@Validated
@ConfigurationProperties(prefix = "bulk.excel")
data class BulkExcelProperties(
    @DefaultValue("SHORT")
    val headers: Headers = Headers(),

    @DefaultValue("true")
    val coercion: Coercion = Coercion(),

    @DefaultValue("true")
    val array: ArrayMode = ArrayMode()
) {
    data class Headers(
        @DefaultValue("SHORT")
        val mode: HeaderMode = HeaderMode.SHORT,
        @DefaultValue("EXPECTED_")
        val prefix: String = "",
    )

    data class Coercion(
        /** If true, refuse non-whole decimals for INT/LONG fields (keep template value) */
        @DefaultValue("true")
        val strictInt: Boolean = true,
        /** Excel date → string format */
        @DefaultValue("yyyy-MM-dd'T'HH:mm:ss")
        val dateFormat: String = "yyyy-MM-dd'T'HH:mm:ss",
    )

    data class ArrayMode(
        /** Follow the [0] convention only (your current behavior) */
        @DefaultValue("true")
        val firstElementOnly: Boolean = true
    )
}