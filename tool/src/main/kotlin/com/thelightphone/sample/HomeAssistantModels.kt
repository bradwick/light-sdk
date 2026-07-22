package com.thelightphone.sample

import kotlinx.serialization.Serializable

@Serializable
data class HaCredentials(
    val serverUrl: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: Long = 0L
)

@Serializable
data class HaEntity(
    val entityId: String,
    val name: String,
    val state: String,
    val temperature: Double? = null,
    val currentTemperature: Double? = null,
    val hvacModes: List<String> = emptyList(),
    val fields: Map<String, HaField> = emptyMap()
) {
    val domain: String
        get() = entityId.substringBefore(".")
}

@Serializable
data class HaField(
    val name: String = "",
    val description: String = ""
)

@Serializable
data class HaTemplateResponse(
    val lights: List<HaEntity> = emptyList(),
    val scripts: List<HaEntity> = emptyList(),
    val climate: List<HaEntity> = emptyList()
)

@Serializable
data class HaLoginFlowStartResponse(
    val flow_id: String,
    val type: String
)

@Serializable
data class HaLoginFlowStepResponse(
    val flow_id: String,
    val type: String,
    val result: String? = null,
    val code: String? = null
)

@Serializable
data class HaTokenResponse(
    val access_token: String,
    val expires_in: Long,
    val refresh_token: String? = null,
    val token_type: String
)
