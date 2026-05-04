package com.nayak.app.user.app

import java.util.*

data class UserDto(
    val id: UUID,
    val racfId: String,
    val roles: Set<String>,
    val isEnabled: Boolean
)
