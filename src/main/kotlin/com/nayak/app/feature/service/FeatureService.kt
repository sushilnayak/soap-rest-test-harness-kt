package com.nayak.app.feature.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.fasterxml.jackson.databind.JsonNode
import com.nayak.app.common.errors.DomainError
import com.nayak.app.feature.api.CucumberFeatureRequest
import com.nayak.app.feature.api.GatlingTestRequest
import com.nayak.app.feature.api.TestFileType
import com.nayak.app.feature.api.TestGenerationResult
import com.nayak.app.project.app.ProjectService
import com.nayak.app.project.model.Project
import com.nayak.app.project.model.ProjectType
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Service
class FeatureService(private val projectService: ProjectService) {
    suspend fun generateGatlingTest(
        request: GatlingTestRequest,
        ownerId: String
    ): Either<DomainError, TestGenerationResult> {
        return projectService.findProjectById(request.projectId).fold(
            ifLeft = { it.left() },
            ifRight = { project ->
                val content = buildGatlingTest(project, request)
                TestGenerationResult(
                    fileName = "${request.testName.replace(" ", "")}Simulation.scala",
                    content = content,
                    fileType = TestFileType.GATLING_SCALA
                ).right()
            }
        )
    }

    suspend fun generateCucumberFeature(
        request: CucumberFeatureRequest,
        ownerId: String
    ): Either<DomainError, TestGenerationResult> {
        return projectService.findProjectById(request.projectId).fold(
            ifLeft = { it.left() },
            ifRight = { project ->
                val content = buildCucumberFeature(project, request)
                TestGenerationResult(
                    fileName = "${request.featureName.replace(" ", "_").lowercase()}.feature",
                    content = content,
                    fileType = TestFileType.CUCUMBER_FEATURE
                ).right()
            }
        )
    }

    private fun buildGatlingTest(
        project: Project,
        request: GatlingTestRequest
    ): String {
        val className = "${request.testName.replace(" ", "")}Simulation"
        val baseUrl = request.baseUrl ?: extractBaseUrl(project)

        return """
package simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

class $className extends Simulation {

  // HTTP configuration
  val httpProtocol = http
    .baseUrl("$baseUrl")
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .userAgentHeader("Gatling Performance Test")

  // Scenario definition
  val scn = scenario("${request.testName}")
    ${buildGatlingScenario(project, request)}

  // Load simulation
  setUp(
    scn.inject(
      rampUsers(${request.userCount}) during (${request.rampUpDuration} seconds)
    )
  ).protocols(httpProtocol)
   .maxDuration(${request.testDuration} seconds)
   .assertions(
     global.responseTime.max.lt(5000),
     global.responseTime.mean.lt(1000),
     global.successfulRequests.percent.gt(95)
   )
}
        """.trimIndent()
    }

    private fun buildGatlingScenario(
        project: Project,
        request: GatlingTestRequest
    ): String {
        val scenarios = mutableListOf<String>()

        when (project.type) {
            ProjectType.REST -> {
                val endpoints = project.meta.get("endpoints")
                if (endpoints?.isArray == true) {
                    endpoints.forEach { endpoint ->
                        val path = endpoint.get("path")?.asText() ?: "/"
                        val method = endpoint.get("method")?.asText()?.lowercase() ?: "get"

                        scenarios.add(
                            """
    .exec(
      http("${method.uppercase()} $path")
        .$method("$path")
        ${if (method == "post" || method == "put") buildRequestBody(project.meta) else ""}
        .check(status.is(200))
    )"""
                        )
                    }
                } else {
                    scenarios.add(
                        """
    .exec(
      http("Default Request")
        .get("/")
        .check(status.is(200))
    )"""
                    )
                }
            }

            ProjectType.SOAP -> {
                val operations = project.meta.get("operations")
                if (operations?.isArray == true) {
                    operations.forEach { operation ->
                        val operationName = operation.asText()
                        scenarios.add(
                            """
    .exec(
      http("SOAP $operationName")
        .post("/")
        .header("Content-Type", "text/xml; charset=utf-8")
        .header("SOAPAction", "$operationName")
        ${buildSoapRequestBody(project.meta)}
        .check(status.is(200))
    )"""
                        )
                    }
                }
            }
        }

        if (request.includeThinkTime) {
            scenarios.add(
                """
    .pause(${request.thinkTimeMin}, ${request.thinkTimeMax})"""
            )
        }

        return scenarios.joinToString("\n")
    }

    private fun buildRequestBody(meta: JsonNode): String {
        val requestTemplate = meta.get("requestTemplate")
        return if (requestTemplate != null) {
            val bodyContent = requestTemplate.toString().replace("\"", "\\\"")
            """.body(StringBody("$bodyContent"))"""
        } else {
            """.body(StringBody("{\"test\": \"data\"}"))"""
        }
    }

    private fun buildSoapRequestBody(meta: JsonNode): String {
        val requestTemplate = meta.get("requestTemplate")
        return if (requestTemplate != null) {
            val bodyContent = requestTemplate.asText().replace("\"", "\\\"")
            """.body(StringBody("$bodyContent"))"""
        } else {
            """.body(StringBody("<?xml version=\"1.0\" encoding=\"utf-8\"?><soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body></soap:Body></soap:Envelope>"))"""
        }
    }

    private fun buildCucumberFeature(
        project: Project,
        request: CucumberFeatureRequest
    ): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        return """
Feature: ${request.featureName}
  As a Test engineer
  I want to test the ${project.name} API
  So that I can ensure it works correctly
  
  # Generated on: $timestamp
  # Project Type: ${project.type}
  # Project ID: ${project.id}

Background:
  Given the API is available
  And I have valid authentication credentials

${buildCucumberScenarios(project, request)}
        """.trimIndent()
    }

    private fun buildCucumberScenarios(
        project: Project,
        request: CucumberFeatureRequest
    ): String {
        val scenarios = mutableListOf<String>()

        when (project.type) {
            ProjectType.REST -> {
                val endpoints = project.meta.get("endpoints")
                if (endpoints?.isArray == true) {
                    endpoints.forEach { endpoint ->
                        val path = endpoint.get("path")?.asText() ?: "/"
                        val method = endpoint.get("method")?.asText()?.uppercase() ?: "GET"

                        scenarios.add(
                            """
Scenario: Successfully call $method $path
  When I send a $method request to "$path"
  ${if (method == "POST" || method == "PUT") "And the request body contains valid data" else ""}
  Then the response status should be 200
  ${if (request.includeValidation) "And the response should contain expected data" else ""}
"""
                        )

                        if (request.includeErrorScenarios) {
                            scenarios.add(
                                """
Scenario: Handle invalid request to $method $path
  When I send a $method request to "$path" with invalid data
  Then the response status should be 400
  And the response should contain error details
"""
                            )
                        }
                    }
                }
            }

            ProjectType.SOAP -> {
                val operations = project.meta.get("operations")
                if (operations?.isArray == true) {
                    operations.forEach { operation ->
                        val operationName = operation.asText()

                        scenarios.add(
                            """
Scenario: Successfully call SOAP operation $operationName
  When I send a SOAP request for operation "$operationName"
  And the SOAP envelope contains valid data
  Then the response status should be 200
  And the SOAP response should be valid
  ${if (request.includeValidation) "And the response should contain expected data" else ""}
"""
                        )

                        if (request.includeErrorScenarios) {
                            scenarios.add(
                                """
Scenario: Handle SOAP fault for operation $operationName
  When I send a SOAP request for operation "$operationName" with invalid data
  Then the response should contain a SOAP fault
  And the fault should have appropriate error details
"""
                            )
                        }
                    }
                }
            }
        }

        return scenarios.joinToString("\n")
    }

    private fun extractBaseUrl(project: Project): String {
        return when (project.type) {
            ProjectType.REST -> project.meta.get("baseUrl")?.asText() ?: "http://localhost:8080"
            ProjectType.SOAP -> project.meta.get("wsdlUrl")?.asText()?.substringBefore("?wsdl")
                ?: "http://localhost:8080"
        }
    }
}