package com.thelightphone.sample

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LoginMethod {
    UsernamePassword,
    AccessToken
}

enum class HaTab {
    Lights,
    Climate,
    Scripts
}

object HaPreferences {
    val SERVER_URL = stringPreferencesKey("ha_server_url")
    val ACCESS_TOKEN = stringPreferencesKey("ha_access_token")
    val REFRESH_TOKEN = stringPreferencesKey("ha_refresh_token")
    val EXPIRES_AT = stringPreferencesKey("ha_expires_at")
}

class HomeScreenViewModel(
    private val dataStore: DataStore<Preferences>
) : LightViewModel<Unit>() {

    private val api = HomeAssistantApi()

    private val _credentials = MutableStateFlow<HaCredentials?>(null)
    val credentials: StateFlow<HaCredentials?> = _credentials.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

    // Dashboard dynamic entities
    private val _entities = MutableStateFlow<HaTemplateResponse?>(null)
    val entities: StateFlow<HaTemplateResponse?> = _entities.asStateFlow()

    init {
        viewModelScope.launch {
            loadCredentials()
        }
    }

    private suspend fun loadCredentials() {
        val prefs = dataStore.data.first()
        val url = prefs[HaPreferences.SERVER_URL]
        val accessToken = prefs[HaPreferences.ACCESS_TOKEN]
        val refreshToken = prefs[HaPreferences.REFRESH_TOKEN]
        val expiresAt = prefs[HaPreferences.EXPIRES_AT]?.toLongOrNull() ?: 0L

        if (!url.isNullOrEmpty() && !accessToken.isNullOrEmpty()) {
            val creds = HaCredentials(url, accessToken, refreshToken, expiresAt)
            _credentials.value = creds
            // Try refreshing or loading entities
            refreshAndFetch(creds)
        } else {
            _credentials.value = null
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun clearSuccessMessage() {
        _successMessage.value = null
    }

    private fun showTemporarySuccess(message: String) {
        _successMessage.value = message
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            if (_successMessage.value == message) {
                _successMessage.value = null
            }
        }
    }

    private fun refreshAndFetch(creds: HaCredentials) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                // If there's a refresh token and we're expired (or close to), refresh it
                var currentCreds = creds
                val expiresAt = creds.expiresAt
                val refreshToken = creds.refreshToken
                if (expiresAt > 0L && System.currentTimeMillis() > expiresAt - 60000L && !refreshToken.isNullOrEmpty()) {
                    try {
                        val tokenResponse = api.refreshAccessToken(creds.serverUrl, refreshToken)
                        val newCreds = HaCredentials(
                            serverUrl = creds.serverUrl,
                            accessToken = tokenResponse.access_token,
                            refreshToken = tokenResponse.refresh_token ?: refreshToken,
                            expiresAt = System.currentTimeMillis() + (tokenResponse.expires_in * 1000L)
                        )
                        saveCredentials(newCreds)
                        currentCreds = newCreds
                    } catch (e: Exception) {
                        Log.e("HomeAssistant", "Token refresh failed", e)
                    }
                }

                val response = api.fetchEntities(currentCreds)
                _entities.value = response
                _error.value = null
            } catch (e: Exception) {
                Log.e("HomeAssistant", "Failed to fetch entities", e)
                _error.value = "Failed to sync with Home Assistant. Check server URL and credentials."
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refreshDashboard() {
        val creds = _credentials.value ?: return
        refreshAndFetch(creds)
    }

    fun loginWithToken(serverUrl: String, token: String) {
        if (serverUrl.isBlank() || token.isBlank()) {
            _error.value = "Please fill in all fields."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null
            try {
                val creds = HaCredentials(serverUrl = serverUrl, accessToken = token)
                // Test the connection
                api.fetchEntities(creds)
                saveCredentials(creds)
                _credentials.value = creds
                refreshAndFetch(creds)
            } catch (e: Exception) {
                Log.e("HomeAssistant", "Login with token failed", e)
                _error.value = "Failed to connect. Please verify your Server URL and Access Token."
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loginWithCredentials(serverUrl: String, username: String, password: String) {
        if (serverUrl.isBlank() || username.isBlank() || password.isBlank()) {
            _error.value = "Please fill in all fields."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null
            try {
                val flowId = api.startLoginFlow(serverUrl)
                val code = api.submitLoginCredentials(serverUrl, flowId, username, password)
                val tokenResponse = api.exchangeCodeForToken(serverUrl, code)

                val creds = HaCredentials(
                    serverUrl = serverUrl,
                    accessToken = tokenResponse.access_token,
                    refreshToken = tokenResponse.refresh_token,
                    expiresAt = System.currentTimeMillis() + (tokenResponse.expires_in * 1000L)
                )

                saveCredentials(creds)
                _credentials.value = creds
                refreshAndFetch(creds)
            } catch (e: Exception) {
                Log.e("HomeAssistant", "Login with credentials failed", e)
                _error.value = "Failed to login: ${e.message ?: "Invalid username/password or Server URL"}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun logout() {
        viewModelScope.launch(Dispatchers.IO) {
            dataStore.edit { prefs ->
                prefs.remove(HaPreferences.SERVER_URL)
                prefs.remove(HaPreferences.ACCESS_TOKEN)
                prefs.remove(HaPreferences.REFRESH_TOKEN)
                prefs.remove(HaPreferences.EXPIRES_AT)
            }
            _credentials.value = null
            _entities.value = null
        }
    }

    private suspend fun saveCredentials(creds: HaCredentials) {
        dataStore.edit { prefs ->
            prefs[HaPreferences.SERVER_URL] = creds.serverUrl
            prefs[HaPreferences.ACCESS_TOKEN] = creds.accessToken
            prefs[HaPreferences.REFRESH_TOKEN] = creds.refreshToken ?: ""
            prefs[HaPreferences.EXPIRES_AT] = creds.expiresAt.toString()
        }
    }

    // --- Entity Actions ---

    fun toggleLightEntity(entity: HaEntity) {
        val creds = _credentials.value ?: return
        val currentOn = entity.state == "on"
        viewModelScope.launch(Dispatchers.IO) {
            val success = api.toggleLight(creds, entity.entityId, !currentOn)
            if (success) {
                // Update local state immediately for great responsiveness
                val updatedEntities = _entities.value?.let { current ->
                    current.copy(
                        lights = current.lights.map {
                            if (it.entityId == entity.entityId) it.copy(state = if (currentOn) "off" else "on") else it
                        }
                    )
                }
                _entities.value = updatedEntities
                showTemporarySuccess("Toggled ${entity.name}")
            } else {
                _error.value = "Failed to toggle ${entity.name}"
            }
        }
    }

    fun adjustClimateTemperature(entity: HaEntity, change: Double) {
        val creds = _credentials.value ?: return
        val currentTarget = entity.temperature ?: 21.0
        val newTarget = currentTarget + change
        viewModelScope.launch(Dispatchers.IO) {
            val success = api.setClimateTemperature(creds, entity.entityId, newTarget)
            if (success) {
                // Update local state
                val updatedEntities = _entities.value?.let { current ->
                    current.copy(
                        climate = current.climate.map {
                            if (it.entityId == entity.entityId) it.copy(temperature = newTarget) else it
                        }
                    )
                }
                _entities.value = updatedEntities
                showTemporarySuccess("Set ${entity.name} to ${newTarget}°")
            } else {
                _error.value = "Failed to set temperature for ${entity.name}"
            }
        }
    }

    fun toggleClimateHvacMode(entity: HaEntity) {
        val creds = _credentials.value ?: return
        if (entity.hvacModes.isEmpty()) return
        val currentIndex = entity.hvacModes.indexOf(entity.state)
        val nextIndex = (currentIndex + 1) % entity.hvacModes.size
        val nextMode = entity.hvacModes[nextIndex]

        viewModelScope.launch(Dispatchers.IO) {
            val success = api.setClimateHvacMode(creds, entity.entityId, nextMode)
            if (success) {
                // Update local state
                val updatedEntities = _entities.value?.let { current ->
                    current.copy(
                        climate = current.climate.map {
                            if (it.entityId == entity.entityId) it.copy(state = nextMode) else it
                        }
                    )
                }
                _entities.value = updatedEntities
                showTemporarySuccess("Set ${entity.name} HVAC to $nextMode")
            } else {
                _error.value = "Failed to set HVAC mode for ${entity.name}"
            }
        }
    }

    fun triggerScriptEntity(entity: HaEntity, params: Map<String, String>) {
        val creds = _credentials.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val success = api.triggerScript(creds, entity.entityId, params)
            if (success) {
                showTemporarySuccess("Triggered ${entity.name}")
            } else {
                _error.value = "Failed to trigger script ${entity.name}"
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        api.close()
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, HomeScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeScreenViewModel>
        get() = HomeScreenViewModel::class.java

    override fun createViewModel(): HomeScreenViewModel {
        return HomeScreenViewModel(lightContext.dataStore)
    }

    @Composable
    override fun Content() {
        val credentials by viewModel.credentials.collectAsState()
        val isLoading by viewModel.isLoading.collectAsState()
        val error by viewModel.error.collectAsState()
        val successMessage by viewModel.successMessage.collectAsState()
        val entities by viewModel.entities.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                if (credentials == null) {
                    LoginView(
                        isLoading = isLoading,
                        onLoginWithToken = viewModel::loginWithToken,
                        onLoginWithCredentials = viewModel::loginWithCredentials
                    )
                } else {
                    DashboardView(
                        entities = entities,
                        isLoading = isLoading,
                        onRefresh = viewModel::refreshDashboard,
                        onLogout = viewModel::logout,
                        onToggleLight = viewModel::toggleLightEntity,
                        onAdjustClimate = viewModel::adjustClimateTemperature,
                        onToggleHvac = viewModel::toggleClimateHvacMode,
                        onTriggerScript = viewModel::triggerScriptEntity
                    )
                }

                // Error handling fullscreen modal
                error?.let { msg ->
                    LightFullscreenModal(
                        message = msg,
                        onClose = { viewModel.clearError() }
                    )
                }

                // Transient Success Toast overlay
                successMessage?.let { msg ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 64.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Row(
                            modifier = Modifier
                                .background(LightThemeTokens.colors.content)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LightIcon(
                                icon = LightIcons.ACCEPT,
                                size = 20f,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            LightText(
                                text = msg,
                                variant = LightTextVariant.Detail
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun LoginView(
        isLoading: Boolean,
        onLoginWithToken: (String, String) -> Unit,
        onLoginWithCredentials: (String, String, String) -> Unit
    ) {
        var loginMethod by remember { mutableStateOf(LoginMethod.UsernamePassword) }
        var serverUrl by remember { mutableStateOf("") }
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var accessToken by remember { mutableStateOf("") }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            LightText(
                text = "Connect Home Assistant",
                variant = LightTextVariant.Heading,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LightTextField(
                label = "Server URL",
                value = serverUrl,
                placeholder = "http://192.168.1.100:8123",
                onClick = {
                    navigateTo(
                        screenFactory = { UiDemoTextInputEditorScreen(it, EditorRequest("Server URL", serverUrl)) },
                        resultCallback = { if (it != null) serverUrl = it }
                    )
                }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                LightText(
                    text = "Method:",
                    variant = LightTextVariant.Detail,
                    lighten = true
                )
                LightText(
                    text = if (loginMethod == LoginMethod.UsernamePassword) "[ USER/PASS ]" else "[ ACCESS TOKEN ]",
                    variant = LightTextVariant.Detail,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.lightClickable {
                        loginMethod = if (loginMethod == LoginMethod.UsernamePassword) {
                            LoginMethod.AccessToken
                        } else {
                            LoginMethod.UsernamePassword
                        }
                    }
                )
            }

            if (loginMethod == LoginMethod.UsernamePassword) {
                LightTextField(
                    label = "Username",
                    value = username,
                    placeholder = "homeassistant",
                    onClick = {
                        navigateTo(
                            screenFactory = { UiDemoTextInputEditorScreen(it, EditorRequest("Username", username)) },
                            resultCallback = { if (it != null) username = it }
                        )
                    }
                )

                LightTextField(
                    label = "Password",
                    value = password,
                    placeholder = "••••••••",
                    onClick = {
                        navigateTo(
                            screenFactory = { UiDemoTextInputEditorScreen(it, EditorRequest("Password", password)) },
                            resultCallback = { if (it != null) password = it }
                        )
                    }
                )
            } else {
                LightTextField(
                    label = "Long-Lived Access Token",
                    value = accessToken,
                    placeholder = "eyJhbGciOi...",
                    onClick = {
                        navigateTo(
                            screenFactory = { UiDemoTextInputEditorScreen(it, EditorRequest("Long-Lived Access Token", accessToken)) },
                            resultCallback = { if (it != null) accessToken = it }
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            if (isLoading) {
                LightText(
                    text = "Connecting...",
                    variant = LightTextVariant.Copy,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LightText(
                    text = "LOGIN",
                    variant = LightTextVariant.Heading,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable {
                            if (loginMethod == LoginMethod.UsernamePassword) {
                                onLoginWithCredentials(serverUrl, username, password)
                            } else {
                                onLoginWithToken(serverUrl, accessToken)
                            }
                        }
                        .padding(vertical = 12.dp)
                )
            }
        }
    }

    @Composable
    private fun DashboardView(
        entities: HaTemplateResponse?,
        isLoading: Boolean,
        onRefresh: () -> Unit,
        onLogout: () -> Unit,
        onToggleLight: (HaEntity) -> Unit,
        onAdjustClimate: (HaEntity, Double) -> Unit,
        onToggleHvac: (HaEntity) -> Unit,
        onTriggerScript: (HaEntity, Map<String, String>) -> Unit
    ) {
        var selectedTab by remember { mutableStateOf(HaTab.Lights) }

        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.REFRESH,
                    onClick = onRefresh,
                    contentDescription = "Refresh"
                ),
                center = LightTopBarCenter.Text("HA Control"),
                rightButton = LightBarButton.Text(
                    text = "LOGOUT",
                    onClick = onLogout
                )
            )

            // Dynamic setup info bar (Home Assistant customizable area/group notification)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                LightText(
                    text = "Curation: Configure 'Light Phone' Area/Group on PC",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Tab selectors
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                TabItem("LIGHTS", selectedTab == HaTab.Lights) { selectedTab = HaTab.Lights }
                TabItem("CLIMATE", selectedTab == HaTab.Climate) { selectedTab = HaTab.Climate }
                TabItem("SCRIPTS", selectedTab == HaTab.Scripts) { selectedTab = HaTab.Scripts }
            }

            // Tab Content scrollable list
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                if (isLoading) {
                    LightText(
                        text = "Syncing with Home Assistant...",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.padding(vertical = 32.dp),
                        textAlign = TextAlign.Center
                    )
                } else if (entities == null) {
                    LightText(
                        text = "No connection data. Please refresh.",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.padding(vertical = 32.dp),
                        textAlign = TextAlign.Center
                    )
                } else {
                    when (selectedTab) {
                        HaTab.Lights -> LightsTabContent(entities.lights, onToggleLight)
                        HaTab.Climate -> ClimateTabContent(entities.climate, onAdjustClimate, onToggleHvac)
                        HaTab.Scripts -> ScriptsTabContent(entities.scripts, onTriggerScript)
                    }
                }
            }

            LightBottomBar(
                items = listOf(
                    LightBarButton.LightIcon(
                        icon = LightIcons.SETTINGS,
                        onClick = { LightThemeController.toggle() },
                        contentDescription = "Toggle Theme"
                    ),
                    null,
                    null
                )
            )
        }
    }

    @Composable
    private fun TabItem(label: String, isSelected: Boolean, onClick: () -> Unit) {
        val style = if (isSelected) "[ $label ]" else label
        LightText(
            text = style,
            variant = LightTextVariant.Detail,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier
                .lightClickable(onClick = onClick)
                .padding(8.dp)
        )
    }

    @Composable
    private fun LightsTabContent(
        lights: List<HaEntity>,
        onToggleLight: (HaEntity) -> Unit
    ) {
        if (lights.isEmpty()) {
            EmptyTabMessage("No Light entities found.")
            return
        }

        lights.forEach { light ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable { onToggleLight(light) }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LightText(
                    text = light.name,
                    variant = LightTextVariant.Copy
                )
                LightIcon(
                    icon = if (light.state == "on") LightIcons.TOGGLE_ON else LightIcons.TOGGLE_OFF,
                    size = 28f
                )
            }
        }
    }

    @Composable
    private fun ClimateTabContent(
        climates: List<HaEntity>,
        onAdjustClimate: (HaEntity, Double) -> Unit,
        onToggleHvac: (HaEntity) -> Unit
    ) {
        if (climates.isEmpty()) {
            EmptyTabMessage("No Climate entities found.")
            return
        }

        climates.forEach { climate ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        LightText(
                            text = climate.name,
                            variant = LightTextVariant.Copy,
                            fontWeight = FontWeight.Bold
                        )
                        // HVAC Mode Selector Button
                        Row(
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .lightClickable { onToggleHvac(climate) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LightText(
                                text = "Mode: ${climate.state.uppercase()}",
                                variant = LightTextVariant.Detail,
                                lighten = true
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            LightIcon(icon = LightIcons.REFRESH, size = 12f)
                        }
                    }

                    // Temperature and Adjustment controls
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            LightText(
                                text = "Target: ${climate.temperature?.toInt() ?: "--"}°",
                                variant = LightTextVariant.Copy
                            )
                            if (climate.currentTemperature != null) {
                                LightText(
                                    text = "Current: ${climate.currentTemperature.toInt()}°",
                                    variant = LightTextVariant.Detail,
                                    lighten = true
                                )
                            }
                        }

                        // Adjust Target Temp buttons
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .lightClickable { onAdjustClimate(climate, -1.0) },
                                contentAlignment = Alignment.Center
                            ) {
                                LightIcon(icon = LightIcons.DOWN, size = 20f)
                            }
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .lightClickable { onAdjustClimate(climate, 1.0) },
                                contentAlignment = Alignment.Center
                            ) {
                                LightIcon(icon = LightIcons.UP, size = 20f)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ScriptsTabContent(
        scripts: List<HaEntity>,
        onTriggerScript: (HaEntity, Map<String, String>) -> Unit
    ) {
        if (scripts.isEmpty()) {
            EmptyTabMessage("No Script entities found.")
            return
        }

        scripts.forEach { script ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable {
                        if (script.fields.isNotEmpty()) {
                            // Open script parameter helper screen
                            navigateTo(
                                screenFactory = { ScriptParamsScreen(it, script) },
                                resultCallback = { params ->
                                    if (it != null && params != null) {
                                        onTriggerScript(script, params)
                                    }
                                }
                            )
                        } else {
                            onTriggerScript(script, emptyMap())
                        }
                    }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    LightText(
                        text = script.name,
                        variant = LightTextVariant.Copy
                    )
                    if (script.fields.isNotEmpty()) {
                        LightText(
                            text = "Requires params",
                            variant = LightTextVariant.Detail,
                            lighten = true
                        )
                    }
                }
                LightIcon(
                    icon = LightIcons.PLAY,
                    size = 24f
                )
            }
        }
    }

    @Composable
    private fun EmptyTabMessage(message: String) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            LightText(
                text = message,
                variant = LightTextVariant.Copy,
                lighten = true,
                textAlign = TextAlign.Center
            )
        }
    }
}
