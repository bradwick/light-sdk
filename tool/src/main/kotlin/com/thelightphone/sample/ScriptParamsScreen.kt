package com.thelightphone.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

class ScriptParamsScreen(
    sealedActivity: SealedLightActivity,
    private val script: HaEntity
) : SimpleLightScreen<Map<String, String>>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        var paramsText by remember { mutableStateOf("") }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack(null) },
                        contentDescription = "Back"
                    ),
                    center = LightTopBarCenter.Text("Script Params"),
                    rightButton = null
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp)
                ) {
                    LightText(
                        text = "Script: ${script.name}",
                        variant = LightTextVariant.Heading,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    LightText(
                        text = "Expected Parameters:",
                        variant = LightTextVariant.Detail,
                        fontWeight = FontWeight.Bold,
                        lighten = true
                    )

                    script.fields.forEach { (key, field) ->
                        val desc = if (field.description.isNotEmpty()) " - ${field.description}" else ""
                        val name = if (field.name.isNotEmpty()) field.name else key
                        LightText(
                            text = "• $key ($name)$desc",
                            variant = LightTextVariant.Detail,
                            modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    LightTextField(
                        label = "Parameter Values (one key=value per line)",
                        value = paramsText,
                        placeholder = "e.g.\nbrightness=255\ntime=10",
                        onClick = {
                            navigateTo(
                                screenFactory = { UiDemoTextInputEditorScreen(it, EditorRequest("Parameter Values", paramsText)) },
                                resultCallback = { if (it != null) paramsText = it }
                            )
                        }
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    LightText(
                        text = "TRIGGER SCRIPT",
                        variant = LightTextVariant.Heading,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable {
                                val parsedParams = parseParams(paramsText)
                                goBack(parsedParams)
                            }
                            .padding(vertical = 12.dp)
                    )
                }
            }
        }
    }

    private fun parseParams(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains("=") }
            .forEach { line ->
                val key = line.substringBefore("=").trim()
                val value = line.substringAfter("=").trim()
                if (key.isNotEmpty()) {
                    result[key] = value
                }
            }
        return result
    }
}
