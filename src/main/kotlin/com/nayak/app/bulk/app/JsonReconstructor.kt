package com.nayak.app.bulk.app

import arrow.core.Either
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.nayak.app.bulk.config.BulkExcelProperties
import com.nayak.app.bulk.config.HeaderMode
import com.nayak.app.common.errors.DomainError
import org.slf4j.LoggerFactory

/**
 * Production-grade JSON reconstructor that builds request JSON from Excel row data,
 * respecting cell exclusions (colored cells) by omitting those paths entirely from the output.
 *
 * Key features:
 * - Excluded cells (colored) result in their JSON paths being completely omitted
 * - Supports nested objects and arrays
 * - Type coercion based on template leaf types
 * - Idiomatic Kotlin + Arrow approach
 */
class JsonReconstructor(
    private val objectMapper: ObjectMapper,
    private val excelProps: BulkExcelProperties
) {
    private val logger = LoggerFactory.getLogger(JsonReconstructor::class.java)

    /**
     * Represents the result of scanning a template for path-to-header mappings.
     */
    data class PathMapping(
        val pathToHeader: Map<String, String>,
        val headerToPath: Map<String, String>
    )

    /**
     * Represents a cell's data with its inclusion status.
     */
    data class CellInfo(
        val value: String,
        val isExcluded: Boolean,
        val typeHint: String?
    )

    /**
     * Reconstructs JSON from template and row data, excluding paths where cells are marked as excluded.
     *
     * @param template The JSON template defining the structure
     * @param rowData Map of header names to CellData (includes exclusion flag)
     * @param prefix Header prefix used in Excel columns
     * @return Either a DomainError or the reconstructed JsonNode with excluded paths omitted
     */
    fun reconstructWithExclusions(
        template: JsonNode,
        rowData: Map<String, CellData>,
        prefix: String = excelProps.headers.prefix
    ): Either<DomainError, JsonNode> = Either.catch {
        val useDotNotation = excelProps.headers.mode == HeaderMode.DOT

        // Step 1: Build path-to-header mapping from template
        val pathMapping = buildPathMapping(template, prefix, useDotNotation)

        // Step 2: Build path-to-cellInfo mapping from row data
        val pathToCellInfo = buildPathToCellInfo(pathMapping, rowData, prefix)

        // Step 3: Collect all excluded paths
        val excludedPaths = pathToCellInfo
            .filterValues { it.isExcluded }
            .keys
            .toSet()

        // Step 4: Reconstruct JSON, omitting excluded paths
        reconstructNode(template, "", pathToCellInfo, excludedPaths)
    }.mapLeft { e ->
        logger.error("JSON reconstruction failed", e)
        DomainError.Validation("Failed to reconstruct JSON: ${e.message}")
    }

    /**
     * Builds a bidirectional mapping between JSON paths and Excel header names.
     */
    private fun buildPathMapping(
        template: JsonNode,
        prefix: String,
        useDotNotation: Boolean
    ): PathMapping {
        val pathToHeader = mutableMapOf<String, String>()
        val headerToPath = mutableMapOf<String, String>()

        fun generateHeaderName(path: String): String {
            if (useDotNotation) return path

            val parts = path.split(".")
            var candidate = parts.last().replace(Regex("\\[\\d+]"), "")
            var depth = 1

            while (pathToHeader.values.contains(candidate) && depth <= parts.size) {
                val startIndex = maxOf(0, parts.size - depth - 1)
                candidate = parts.subList(startIndex, parts.size)
                    .joinToString("_") { it.replace(Regex("\\[\\d+]"), "") }
                depth++
            }
            return candidate
        }

        fun traverse(node: JsonNode, path: String) {
            when {
                node.isObject -> {
                    node.fields().forEach { (key, value) ->
                        val newPath = if (path.isEmpty()) key else "$path.$key"
                        traverse(value, newPath)
                    }
                }

                node.isArray -> {
                    if (excelProps.array.firstElementOnly) {
                        if (node.size() > 0) traverse(node[0], "$path[0]")
                    } else {
                        for (i in 0 until node.size()) {
                            traverse(node[i], "$path[$i]")
                        }
                    }
                }

                else -> {
                    // Leaf node
                    val headerName = generateHeaderName(path)
                    val fullHeader = "$prefix$headerName"
                    pathToHeader[path] = fullHeader
                    headerToPath[fullHeader] = path
                }
            }
        }

        traverse(template, "")
        return PathMapping(pathToHeader, headerToPath)
    }

    /**
     * Maps JSON paths to their corresponding cell info from row data.
     */
    private fun buildPathToCellInfo(
        pathMapping: PathMapping,
        rowData: Map<String, CellData>,
        prefix: String
    ): Map<String, CellInfo> {
        val result = mutableMapOf<String, CellInfo>()

        pathMapping.pathToHeader.forEach { (path, header) ->
            rowData[header]?.let { cellData ->
                result[path] = CellInfo(
                    value = cellData.value,
                    isExcluded = cellData.isExcluded,
                    typeHint = cellData.typeHint
                )
            }
        }

        return result
    }

    /**
     * Recursively reconstructs a JSON node, omitting excluded paths.
     *
     * @param template The template node to reconstruct from
     * @param currentPath The current JSON path being processed
     * @param pathToCellInfo Mapping of paths to cell info
     * @param excludedPaths Set of paths that should be excluded
     * @return The reconstructed JsonNode, or null if the entire node should be excluded
     */
    private fun reconstructNode(
        template: JsonNode,
        currentPath: String,
        pathToCellInfo: Map<String, CellInfo>,
        excludedPaths: Set<String>
    ): JsonNode {
        return when {
            template.isObject -> reconstructObject(template, currentPath, pathToCellInfo, excludedPaths)
            template.isArray -> reconstructArray(template, currentPath, pathToCellInfo, excludedPaths)
            else -> reconstructLeaf(template, currentPath, pathToCellInfo, excludedPaths)
        }
    }

    /**
     * Reconstructs an object node, excluding fields whose paths are in the excluded set.
     */
    private fun reconstructObject(
        template: JsonNode,
        currentPath: String,
        pathToCellInfo: Map<String, CellInfo>,
        excludedPaths: Set<String>
    ): ObjectNode {
        val result = objectMapper.createObjectNode()

        template.fields().forEach { (key, value) ->
            val fieldPath = if (currentPath.isEmpty()) key else "$currentPath.$key"

            // Check if this entire subtree should be excluded
            if (shouldExcludeSubtree(fieldPath, excludedPaths, value)) {
                // Skip this field entirely
                return@forEach
            }

            val reconstructedValue = reconstructNode(value, fieldPath, pathToCellInfo, excludedPaths)

            // Only add non-empty objects/arrays or leaf values
            if (!isEmptyContainer(reconstructedValue)) {
                result.set<JsonNode>(key, reconstructedValue)
            }
        }

        return result
    }

    /**
     * Reconstructs an array node, excluding elements whose paths are in the excluded set.
     */
    private fun reconstructArray(
        template: JsonNode,
        currentPath: String,
        pathToCellInfo: Map<String, CellInfo>,
        excludedPaths: Set<String>
    ): ArrayNode {
        val result = objectMapper.createArrayNode()

        if (excelProps.array.firstElementOnly) {
            if (template.size() > 0) {
                val elementPath = "$currentPath[0]"
                if (!shouldExcludeSubtree(elementPath, excludedPaths, template[0])) {
                    val reconstructedElement = reconstructNode(template[0], elementPath, pathToCellInfo, excludedPaths)
                    if (!isEmptyContainer(reconstructedElement)) {
                        result.add(reconstructedElement)
                    }
                }
            }
        } else {
            for (i in 0 until template.size()) {
                val elementPath = "$currentPath[$i]"
                if (!shouldExcludeSubtree(elementPath, excludedPaths, template[i])) {
                    val reconstructedElement = reconstructNode(template[i], elementPath, pathToCellInfo, excludedPaths)
                    if (!isEmptyContainer(reconstructedElement)) {
                        result.add(reconstructedElement)
                    }
                }
            }
        }

        return result
    }

    /**
     * Reconstructs a leaf node, returning the coerced value or template default.
     */
    private fun reconstructLeaf(
        template: JsonNode,
        currentPath: String,
        pathToCellInfo: Map<String, CellInfo>,
        excludedPaths: Set<String>
    ): JsonNode {
        // If this exact path is excluded, return null node (will be filtered by parent)
        if (excludedPaths.contains(currentPath)) {
            return objectMapper.nullNode()
        }

        val cellInfo = pathToCellInfo[currentPath]
        return if (cellInfo != null && !cellInfo.isExcluded) {
            coerceValue(cellInfo.value, cellInfo.typeHint, template)
        } else {
            // No data provided or excluded - keep template value
            template
        }
    }

    /**
     * Determines if an entire subtree should be excluded.
     * A subtree is excluded if:
     * - For leaf nodes: the exact path is in excludedPaths
     * - For container nodes: ALL descendant leaf paths are excluded
     */
    private fun shouldExcludeSubtree(
        path: String,
        excludedPaths: Set<String>,
        node: JsonNode
    ): Boolean {
        // For leaf nodes, check direct exclusion
        if (!node.isObject && !node.isArray) {
            return excludedPaths.contains(path)
        }

        // For containers, check if ALL descendants are excluded
        val descendantPaths = collectAllLeafPaths(node, path)
        return descendantPaths.isNotEmpty() && descendantPaths.all { excludedPaths.contains(it) }
    }

    /**
     * Collects all leaf paths under a given node.
     */
    private fun collectAllLeafPaths(node: JsonNode, basePath: String): Set<String> {
        val paths = mutableSetOf<String>()

        fun traverse(n: JsonNode, path: String) {
            when {
                n.isObject -> {
                    n.fields().forEach { (key, value) ->
                        val newPath = if (path.isEmpty()) key else "$path.$key"
                        traverse(value, newPath)
                    }
                }

                n.isArray -> {
                    if (excelProps.array.firstElementOnly) {
                        if (n.size() > 0) traverse(n[0], "$path[0]")
                    } else {
                        for (i in 0 until n.size()) {
                            traverse(n[i], "$path[$i]")
                        }
                    }
                }

                else -> paths.add(path)
            }
        }

        traverse(node, basePath)
        return paths
    }

    /**
     * Checks if a node is an empty container (empty object or array).
     */
    private fun isEmptyContainer(node: JsonNode): Boolean {
        return (node.isObject && node.isEmpty) || (node.isArray && node.isEmpty)
    }

    /**
     * Coerces a string value to the appropriate JSON type based on hint and template.
     */
    private fun coerceValue(value: String, hint: String?, template: JsonNode): JsonNode {
        val f = objectMapper.nodeFactory
        val trimmed = value.trim()

        if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) {
            return f.nullNode()
        }

        // If template is textual, always return text (preserve IDs like "0012")
        if (template.isTextual) {
            return f.textNode(trimmed)
        }

        // If template is boolean, parse loosely
        if (template.isBoolean) {
            return parseBooleanLoose(trimmed)?.let { f.booleanNode(it) } ?: template
        }

        // If template is numeric, match its numeric kind
        if (template.isNumber) {
            val bd = trimmed.toBigDecimalOrNull() ?: return template
            return when (template.numberType()) {
                com.fasterxml.jackson.core.JsonParser.NumberType.INT -> {
                    val whole = bd.stripTrailingZeros().scale() <= 0
                    if (!whole) return template
                    val asLong = bd.toLong()
                    if (asLong in Int.MIN_VALUE..Int.MAX_VALUE) f.numberNode(asLong.toInt())
                    else f.numberNode(asLong)
                }

                com.fasterxml.jackson.core.JsonParser.NumberType.LONG,
                com.fasterxml.jackson.core.JsonParser.NumberType.BIG_INTEGER -> {
                    val whole = bd.stripTrailingZeros().scale() <= 0
                    if (!whole) return template
                    f.numberNode(bd.toLong())
                }

                else -> f.numberNode(bd)
            }
        }

        // Excel hint-based coercion
        return when (hint) {
            "BOOLEAN" -> parseBooleanLoose(trimmed)?.let { f.booleanNode(it) } ?: f.textNode(trimmed)
            "NUMERIC" -> trimmed.toBigDecimalOrNull()?.let { f.numberNode(it) } ?: f.textNode(trimmed)
            "DATE" -> f.textNode(trimmed)
            else -> {
                // Try number -> boolean -> text
                trimmed.toBigDecimalOrNull()?.let { f.numberNode(it) }
                    ?: parseBooleanLoose(trimmed)?.let { f.booleanNode(it) }
                    ?: f.textNode(trimmed)
            }
        }
    }

    private fun parseBooleanLoose(s: String): Boolean? = when (s.lowercase()) {
        "true", "t", "yes", "y", "1" -> true
        "false", "f", "no", "n", "0" -> false
        else -> null
    }
}
