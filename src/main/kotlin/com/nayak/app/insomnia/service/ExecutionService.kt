package com.nayak.app.insomnia.service

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.nayak.app.common.errors.DomainError
import com.nayak.app.insomnia.api.AuthConfig
import com.nayak.app.insomnia.api.ExecutionRequest
import com.nayak.app.insomnia.api.ExecutionResponse
import com.nayak.app.insomnia.api.RetryConfig
import com.nayak.app.project.model.ProjectType
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.reactive.function.client.awaitExchange
import kotlin.time.DurationUnit
import kotlin.time.measureTimedValue

@Service
class ExecutionService(
    private val webClient: WebClient,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(ExecutionService::class.java)
    private val xmlMapper = XmlMapper()

    suspend fun executeRequest(request: ExecutionRequest): Either<DomainError, ExecutionResponse> =
        either {
            //Resolve token only if required, short-circuiting on failure
            val authToken: String? =
                request.authConfig
                    ?.takeIf { it.requiresAuth }
                    ?.let { getAuthToken(it).bind() }

            // Prepare the request (pure). If this can fail, make it return Either and .bind() it here.
            val prepared = prepareRequest(request, authToken, request.authConfig?.authHeaderKey)
            println(prepared)
            println(request)
            println(authToken)
            val (resp, dur) = try {
                measureTimedValue {
                    executeWithRetry(prepared, request.retryConfig)
                }
            } catch (t: Throwable) {
                raise(DomainError.External("Request execution failed: ${t.message}"))
            }

            ExecutionResponse(
                success = resp.statusCode in 200..299,
                statusCode = resp.statusCode,
                headers = resp.headers,
                responseBody = resp.body,
                executionTimeMs = dur.toLong(DurationUnit.MILLISECONDS),
                retryAttempts = resp.retryAttempts
            )
        }

//    suspend fun executeRequest(request: ExecutionRequest): Either<DomainError, ExecutionResponse> {
//        return try {
//            val startTime = System.currentTimeMillis()
//
//            val authToken = if (request.authConfig?.requiresAuth == true) {
//                getAuthToken(request.authConfig).fold(
//                    ifLeft = { return it.left() },
//                    ifRight = { it }
//                )
//            } else null
//
//            // Prepare the actual request
//            val preparedRequest = prepareRequest(request, authToken, request.authConfig?.authHeaderKey)
//
//            // Execute with retry logic
//            val response = executeWithRetry(preparedRequest, request.retryConfig)
//
//            val executionTime = System.currentTimeMillis() - startTime
//
//            ExecutionResponse(
//                success = response.statusCode in 200..299,
//                statusCode = response.statusCode,
//                headers = response.headers,
//                responseBody = response.body,
//                executionTimeMs = executionTime,
//                retryAttempts = response.retryAttempts
//            ).right()
//
//        } catch (e: Exception) {
//            logger.error("Request execution failed", e)
//            DomainError.External("Request execution failed: ${e.message}").left()
//        }
//    }

    suspend fun getAuthToken(authConfig: AuthConfig): Either<DomainError, String> =
        either {
            try {
                val response = webClient.post()
                    .uri(authConfig.authTokenUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(authConfig.authPayload)
                    .awaitExchange { clientResponse ->
                        if (clientResponse.statusCode().is2xxSuccessful) {
                            // try to deserialize as Map first, else fallback to raw String
                            try {
                                clientResponse.awaitBody<Map<String, Any>>()
                            } catch (_: Exception) {
                                clientResponse.awaitBody<String>()
                            }
                        } else {
                            throw RuntimeException("Token request failed with status: ${clientResponse.statusCode()}")
                        }
                    }

                val accessToken = when (response) {
                    is Map<*, *> -> response[authConfig.authResponseAttribute] as? String
                    is String -> response
                    else -> null
                } ?: raise(DomainError.Authentication("No access token found in response"))

                accessToken
            } catch (e: Exception) {
                logger.error("Token acquisition failed", e)
                raise(DomainError.Authentication("Token acquisition failed: ${e.message}"))
            }
        }

    fun prepareRequest(request: ExecutionRequest, authToken: String?, authTokenHeader: String?): PreparedRequest {
        val headers = HttpHeaders().apply {
            request.headers.forEach { (k, v) -> if (k.isNotBlank()) add(k, v) }
        }

        val token = authToken?.trim().orEmpty()
        if (token.isNotEmpty() && !authTokenHeader.isNullOrBlank()) {
            if (!headers.containsKey(authTokenHeader)) {
                headers[authTokenHeader] = listOf(token)
            }
        }

        val (finalMethod, finalBody) = when (request.requestType) {
            ProjectType.SOAP -> {
                if (!headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
                    headers.contentType =
                        MediaType("text", "xml") // text/xml; charset added by HttpMessageWriter if needed
                }
                // Respect caller-provided SOAPAction; otherwise default to empty
                if (!headers.containsKey("SOAPAction")) {
                    headers["SOAPAction"] = listOf("")
                }
                HttpMethod.POST to request.requestBody
            }

            ProjectType.REST -> {
                if (!headers.containsKey(HttpHeaders.ACCEPT)) {
                    headers.accept = listOf(MediaType.APPLICATION_JSON)
                }
                val hasBody = request.requestBody != null
                if (hasBody && !headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
                    headers.contentType = MediaType.APPLICATION_JSON
                }

                val method = request.httpMethod
                val body = when (method) {
                    HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS -> null
                    else -> request.requestBody
                }
                method to body
            }
        }

        return PreparedRequest(
            url = request.targetUrl,
            method = finalMethod,
            headers = headers.toSingleValueMap(), // collapse to Map<String, String>
            body = finalBody,
            queryParams = request.queryParams,
            timeoutSeconds = request.timeoutSeconds
        )
    }

    suspend fun executeWithRetry(
        request: PreparedRequest,
        retryConfig: RetryConfig?
    ): InternalResponse {
        val maxAttempts = retryConfig?.maxAttempts ?: 1
        var lastException: Exception? = null

        repeat(maxAttempts) { attempt ->
            try {
                val response = executeHttpRequest(request)

                // Check if we should retry based on status code
                if (retryConfig != null &&
                    response.statusCode in retryConfig.retryOnStatusCodes &&
                    attempt < maxAttempts - 1
                ) {
                    delay(retryConfig.backoffDelayMs * (attempt + 1))
                    return@repeat
                }

                return response.copy(retryAttempts = attempt)
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxAttempts - 1) {
                    delay(retryConfig?.backoffDelayMs ?: 1000)
                }
            }
        }

        throw lastException ?: RuntimeException("All retry attempts failed")
    }

    suspend fun executeHttpRequest(request: PreparedRequest): InternalResponse {
        return webClient.method(request.method)
            .uri(request.url) { uriBuilder ->
                request.queryParams.forEach { (key, value) ->
                    uriBuilder.queryParam(key, value)
                }
                uriBuilder.build()
            }
            .headers { httpHeaders ->
                request.headers.forEach { (key, value) ->
                    httpHeaders.set(key, value)
                }
            }
            .apply {
                request.body?.let { body ->
                    bodyValue(
                        if (request.headers["Content-Type"]?.contains("xml") == true) {
                            xmlMapper.writeValueAsString(body)
                        } else {
                            objectMapper.writeValueAsString(body)
                        }
                    )
                }
            }
            //TODO: Fix this
//            .timeout(Duration.ofSeconds(request.timeoutSeconds.toLong()))
            .awaitExchange { clientResponse ->
                val responseBody = try {
                    clientResponse.awaitBody<String>()
                } catch (e: Exception) {
                    null
                }

                InternalResponse(
                    statusCode = clientResponse.statusCode().value(),
                    headers = clientResponse.headers().asHttpHeaders().toMap(),
                    body = responseBody,
                    retryAttempts = 0
                )
            }
    }

    // Format conversion utilities
    fun convertXmlToJson(xmlString: String): Either<DomainError, JsonNode> {
        return try {
            val xmlNode = xmlMapper.readTree(xmlString)
            val jsonString = objectMapper.writeValueAsString(xmlNode)
            objectMapper.readTree(jsonString).right()
        } catch (e: Exception) {
            DomainError.Validation("XML to JSON conversion failed: ${e.message}").left()
        }
    }

    fun convertJsonToXml(jsonNode: JsonNode): Either<DomainError, String> {
        return try {
            xmlMapper.writeValueAsString(jsonNode).right()
        } catch (e: Exception) {
            DomainError.Validation("JSON to XML conversion failed: ${e.message}").left()
        }
    }
}

data class PreparedRequest(
    val url: String,
    val method: HttpMethod,
    val headers: Map<String, String>,
    val body: JsonNode?,
    val queryParams: Map<String, String>,
    val timeoutSeconds: Int
)

data class InternalResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: String?,
    val retryAttempts: Int
)