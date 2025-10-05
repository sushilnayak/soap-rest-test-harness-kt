package com.nayak.app.common.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions
import org.springframework.data.r2dbc.dialect.PostgresDialect

@WritingConverter
class JsonNodeToJsonConverter(
    private val objectMapper: ObjectMapper
) : Converter<JsonNode, Json> {
    override fun convert(source: JsonNode): Json {
        return Json.of(objectMapper.writeValueAsBytes(source))
    }
}

@ReadingConverter
class JsonToJsonNodeConverter(
    private val objectMapper: ObjectMapper
) : Converter<Json, JsonNode> {
    override fun convert(source: Json): JsonNode {
        return objectMapper.readTree(source.asString())
    }
}

@Configuration
class R2dbcConverterConfiguration {


    @Bean
    fun r2dbcCustomConversions(objectMapper: ObjectMapper): R2dbcCustomConversions {
        val converters = listOf(
            JsonNodeToJsonConverter(objectMapper),
            JsonToJsonNodeConverter(objectMapper)
        )

        return R2dbcCustomConversions.of(PostgresDialect.INSTANCE, converters)
    }
}