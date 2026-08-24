package com.air5005.pagenest.speech.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.air5005.pagenest.speech.model.SpeechMode
import com.air5005.pagenest.speech.settings.SpeechSettingsViewModel
import com.wxn.reader.R
import com.wxn.reader.navigation.LocalNavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechSettingsScreen(viewModel: SpeechSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    var region by remember(state.region) { mutableStateOf(state.region) }
    val navController = LocalNavController.current

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.speech_settings)) },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.speech_back))
                }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.speech_mode))
            Row {
                SpeechMode.entries.forEach { mode ->
                    TextButton(onClick = { viewModel.selectMode(mode) }) {
                        Text(stringResource(when (mode) {
                            SpeechMode.OFFLINE -> R.string.speech_mode_offline
                            SpeechMode.ONLINE -> R.string.speech_mode_online
                            SpeechMode.AUTO -> R.string.speech_mode_auto
                        }))
                    }
                }
            }
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(if (state.keyConfigured) stringResource(R.string.speech_key_replace) else stringResource(R.string.speech_key)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = region,
                onValueChange = { region = it },
                label = { Text(stringResource(R.string.speech_region)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.preferences.localeTag,
                onValueChange = viewModel::setLocale,
                label = { Text(stringResource(R.string.speech_locale)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                Button(onClick = { viewModel.saveAzure(key, region); key = "" }) { Text(stringResource(R.string.speech_save)) }
                TextButton(onClick = viewModel::testConnection) { Text(stringResource(R.string.speech_test_connection)) }
                if (state.keyConfigured) {
                    TextButton(onClick = viewModel::deleteAzure) { Text(stringResource(R.string.speech_delete_key)) }
                }
            }
            state.availableVoices.forEach { voice ->
                TextButton(onClick = { viewModel.setVoice(voice.id) }) {
                    Text("${voice.displayName} (${voice.localeTag})")
                }
            }
        }
    }
}
