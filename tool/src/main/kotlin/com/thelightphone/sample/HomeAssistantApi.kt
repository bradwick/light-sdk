package com.thelightphone.sample

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.net.URLEncoder

class HomeAssistantApi {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
    }

    private fun cleanUrl(url: String): String {
        var cleaned = url.trim()
        if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) {
            cleaned = "http://$cleaned"
        }
        return cleaned.removeSuffix("/")
    }

    /**
     * Start the login flow by fetching a flow ID.
     */
    suspend fun startLoginFlow(serverUrl: String): String {
        val base = cleanUrl(serverUrl)
        val response: HaLoginFlowStartResponse = client.post("$base/auth/login_flow") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("client_id", JsonPrimitive("http://localhost"))
                    put("handler", Json.parseToJsonElement("""["homeassistant", null]"""))
                    put("redirect_uri", JsonPrimitive("http://localhost"))
                }
            )
        }.body()
        return response.flow_id
    }

    /**
     * Submit the username and password to the flow ID to get an authorization code.
     */
    suspend fun submitLoginCredentials(serverUrl: String, flowId: String, username: String, password: String): String {
        val base = cleanUrl(serverUrl)
        val response: HaLoginFlowStepResponse = client.post("$base/auth/login_flow/$flowId") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("client_id", JsonPrimitive("http://localhost"))
                    put("username", JsonPrimitive(username))
                    put("password", JsonPrimitive(password))
                }
            )
        }.body()

        val code = response.code ?: response.result
        if (code.isNullOrEmpty()) {
            throw Exception("Credentials rejected or MFA required.")
        }
        return code
    }

    /**
     * Exchange the authorization code for an Access and Refresh Token.
     */
    suspend fun exchangeCodeForToken(serverUrl: String, code: String): HaTokenResponse {
        val base = cleanUrl(serverUrl)
        val formBody = "grant_type=authorization_code" +
                "&code=${URLEncoder.encode(code, "UTF-8")}" +
                "&client_id=${URLEncoder.encode("http://localhost", "UTF-8")}"

        val response: HaTokenResponse = client.post("$base/auth/token") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(formBody)
        }.body()
        return response
    }

    /**
     * Refresh the Access Token using the Refresh Token.
     */
    suspend fun refreshAccessToken(serverUrl: String, refreshToken: String): HaTokenResponse {
        val base = cleanUrl(serverUrl)
        val formBody = "grant_type=refresh_token" +
                "&refresh_token=${URLEncoder.encode(refreshToken, "UTF-8")}" +
                "&client_id=${URLEncoder.encode("http://localhost", "UTF-8")}"

        val response: HaTokenResponse = client.post("$base/auth/token") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(formBody)
        }.body()
        return response
    }

    /**
     * Fetch entities dynamically using Home Assistant Jinja2 template API.
     * This queries the "Light Phone" area or "group.light_phone" if defined on the PC.
     * Otherwise, it fetches all lights, climate entities, and scripts.
     */
    suspend fun fetchEntities(creds: HaCredentials): HaTemplateResponse {
        val base = cleanUrl(creds.serverUrl)
        val template = """
        {% set area_entities_list = area_entities('Light Phone') | default([]) %}
        {% set group_entities_list = expand('group.light_phone') | map(attribute='entity_id') | list if states('group.light_phone') != 'unknown' else [] %}
        {% set filter_list = area_entities_list + group_entities_list %}
        {
          "lights": [
            {% set comma = joiner() %}
            {% for s in states.light %}
              {% if filter_list | length == 0 or s.entity_id in filter_list %}
                {{ comma() }}
                {
                  "entityId": "{{ s.entity_id }}",
                  "name": "{{ s.name }}",
                  "state": "{{ s.state }}"
                }
              {% endif %}
            {% endfor %}
          ],
          "scripts": [
            {% set comma = joiner() %}
            {% for s in states.script %}
              {% if filter_list | length == 0 or s.entity_id in filter_list %}
                {{ comma() }}
                {
                  "entityId": "{{ s.entity_id }}",
                  "name": "{{ s.name }}",
                  "state": "{{ s.state }}",
                  "fields": {
                    {% set inner_comma = joiner() %}
                    {% for f, val in s.attributes.fields | default({}) | items %}
                      {{ inner_comma() }}
                      "{{ f }}": {
                        "name": "{{ val.name | default('') }}",
                        "description": "{{ val.description | default('') }}"
                      }
                    {% endfor %}
                  }
                }
              {% endif %}
            {% endfor %}
          ],
          "climate": [
            {% set comma = joiner() %}
            {% for s in states.climate %}
              {% if filter_list | length == 0 or s.entity_id in filter_list %}
                {{ comma() }}
                {
                  "entityId": "{{ s.entity_id }}",
                  "name": "{{ s.name }}",
                  "state": "{{ s.state }}",
                  "temperature": {{ s.attributes.temperature | default('null') }},
                  "currentTemperature": {{ s.attributes.current_temperature | default('null') }},
                  "hvacModes": {{ s.attributes.hvac_modes | default([]) | tojson }}
                }
              {% endif %}
            {% endfor %}
          ]
        }
        """.trimIndent()

        val rawResponse: String = client.post("$base/api/template") {
            header("Authorization", "Bearer ${creds.accessToken}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("template", JsonPrimitive(template))
            })
        }.body()
        return Json { ignoreUnknownKeys = true }.decodeFromString<HaTemplateResponse>(rawResponse)
    }

    /**
     * Call service to toggle light/switch.
     */
    suspend fun toggleLight(creds: HaCredentials, entityId: String, turnOn: Boolean): Boolean {
        val base = cleanUrl(creds.serverUrl)
        val service = if (turnOn) "turn_on" else "turn_off"
        val domain = entityId.substringBefore(".")
        val url = "$base/api/services/$domain/$service"

        val response: HttpResponse = client.post(url) {
            header("Authorization", "Bearer ${creds.accessToken}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("entity_id", JsonPrimitive(entityId))
            })
        }
        return response.status.value in 200..299
    }

    /**
     * Trigger script with optional parameters.
     */
    suspend fun triggerScript(creds: HaCredentials, entityId: String, params: Map<String, String>): Boolean {
        val base = cleanUrl(creds.serverUrl)
        val scriptName = entityId.substringAfter(".")
        val url = "$base/api/services/script/$scriptName"

        val response: HttpResponse = client.post(url) {
            header("Authorization", "Bearer ${creds.accessToken}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                params.forEach { (k, v) ->
                    // Try to parse numerical values if possible
                    val num = v.toDoubleOrNull()
                    if (num != null) {
                        put(k, JsonPrimitive(num))
                    } else {
                        put(k, JsonPrimitive(v))
                    }
                }
            })
        }
        return response.status.value in 200..299
    }

    /**
     * Change climate settings (temperature or hvac mode).
     */
    suspend fun setClimateTemperature(creds: HaCredentials, entityId: String, temp: Double): Boolean {
        val base = cleanUrl(creds.serverUrl)
        val url = "$base/api/services/climate/set_temperature"

        val response: HttpResponse = client.post(url) {
            header("Authorization", "Bearer ${creds.accessToken}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("entity_id", JsonPrimitive(entityId))
                put("temperature", JsonPrimitive(temp))
            })
        }
        return response.status.value in 200..299
    }

    suspend fun setClimateHvacMode(creds: HaCredentials, entityId: String, mode: String): Boolean {
        val base = cleanUrl(creds.serverUrl)
        val url = "$base/api/services/climate/set_hvac_mode"

        val response: HttpResponse = client.post(url) {
            header("Authorization", "Bearer ${creds.accessToken}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("entity_id", JsonPrimitive(entityId))
                put("hvac_mode", JsonPrimitive(mode))
            })
        }
        return response.status.value in 200..299
    }

    fun close() {
        client.close()
    }
}
