package com.nayak.app.common.http

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val message: String? = null,
    val error: String? = null,
    val timestamp: Instant = Instant.now(),
) {

    companion object {
        fun <T> success(data: T, message: String? = null): ApiResponse<T> = ApiResponse(true, data = data, message = message)
        fun <T> error(error: String, data: T? = null): ApiResponse<T> = ApiResponse(false, error = error , data = data)
    }
}