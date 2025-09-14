package com.nayak.app.insomnia.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.nayak.app.common.errors.DomainError
import com.nayak.app.insomnia.api.AuthConfig
import com.nayak.app.insomnia.api.InsomniaRequest
import com.nayak.app.insomnia.api.InsomniaResponse
import com.nayak.app.insomnia.api.RetryConfig
import com.nayak.app.project.model.ProjectType
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.reactive.function.client.awaitExchange

@Service
class InsomniaService(
    private val webClient: WebClient,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(InsomniaService::class.java)
    private val xmlMapper = XmlMapper()

    suspend fun executeRequest(request: InsomniaRequest): Either<DomainError, InsomniaResponse> {
        return try {
            val startTime = System.currentTimeMillis()

            val authToken = if (request.authConfig?.required == true) {
                getAuthToken(request.authConfig).fold(
                    ifLeft = { return it.left() },
                    ifRight = { it }
                )
            } else null

            val preparedRequest = prepareRequest(request, authToken)

            val response = executeWithRetry(preparedRequest, request.retryConfig)

            val executionTime = System.currentTimeMillis() - startTime

            InsomniaResponse(
                success = response.statusCode in 200..299,
                statusCode = response.statusCode,
                headers = response.headers,
                responseBody = response.body,
                executionTimeMs = executionTime,
                retryAttempts = response.retryAttempts,
            ).right()

        } catch (e: Exception) {
            logger.error("Request execution failed", e)
            DomainError.External("Request failed with message : ${e.message}").left()
        }
    }

    suspend fun getAuthToken(authConfig: AuthConfig): Either<DomainError, String> {
        return try {
//            val tokenRequest = buildMap {
//                put("grant_type", authConfig.grantType)
//                authConfig.clientId?.let { put("client_id", it) }
//                authConfig.clientSecret?.let { put("client_secret", it) }
//                authConfig.audience?.let { put("audience", it) }
//                authConfig.scope?.let { put("scope", it) }
//                putAll(authConfig.additionalParams)
//            }
            val response = webClient.post()
                .uri(authConfig.tokenUrl!!)
                .contentType(MediaType.APPLICATION_JSON)
//                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
//                .bodyValue(tokenRequest.map { "${it.key}=${it.value}" }.joinToString("&"))
                .bodyValue(
                    """
                    {}
                """.trimIndent()
                )
                .awaitExchange { clientResponse ->
                    if (clientResponse.statusCode().is2xxSuccessful) {
                        clientResponse.awaitBody<Map<String, Any>>()
                    } else {
                        throw RuntimeException("Token request failed with response code ${clientResponse.statusCode()}")
                    }
                }

            val accessToken = response["access_token"] as? String
                ?: return DomainError.Authentication("No access token in response").left()

            accessToken.right()

        } catch (e: Exception) {
            logger.error("Token acquisition failed", e)
            DomainError.Authentication("Token acquisition failed: ${e.message}").left()
        }
    }

    suspend fun prepareRequest(request: InsomniaRequest, authToken: String?): PreparedRequest {
        val headers = request.headers.toMutableMap()

        authToken?.let { headers["Authorization"] = "Bearer $it" }

        val (finalMethod, finalBody, finalHeaders) = when (request.requestType) {
            ProjectType.SOAP -> {
                headers["Content-Type"] = "text/xml; charset=utf-8"
                headers["SOAPAction"] = "\"\"" // Default SOAP action
                Triple(HttpMethod.POST, request.requestBody, headers)
            }

            ProjectType.REST -> {
                if (request.requestBody != null && !headers.containsKey("Content-Type")) {
                    headers["Content-Type"] = "application/json"
                }
                Triple(request.httpMethod, request.requestBody, headers)
            }
        }

        return PreparedRequest(
            url = request.targetUrl,
            method = finalMethod,
            headers = finalHeaders,
            body = finalBody,
            queryParams = request.queryParams,
            timeoutSeconds = request.timeoutSeconds
        )

    }

    private suspend fun executeWithRetry(
        request: PreparedRequest,
        retryConfig: RetryConfig?
    ): InternalResponse {
        val maxAttempts = retryConfig?.maxRetries ?: 1
        var lastException: Exception? = null

        repeat(maxAttempts) { attempt ->
            try {
                val response = executeHttpRequest(request)

                if (retryConfig != null &&
                    response.statusCode in retryConfig.retryOnStatusCodes &&
                    attempt < maxAttempts - 1) {
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
        return webClient
            .method(request.method)
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
                    bodyValue(if (request.headers["Content-Type"]?.contains("xml") == true) {
                        xmlMapper.writeValueAsString(body)
                    } else {
                        objectMapper.writeValueAsString(body)
                    })
                }
            }
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