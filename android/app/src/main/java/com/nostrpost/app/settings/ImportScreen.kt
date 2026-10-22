package com.nostrpost.app.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nostrpost.app.R

@Composable
fun ImportScreen(
    onImport: (String) -> Unit,
    onBack: () -> Unit,
    error: String? = null
) {
    var input by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val trimmed = input.trim()
    val isValid = trimmed.isNotEmpty() && (
        trimmed.startsWith("nsec1") || trimmed.matches(Regex("[0-9a-fA-F]{64}"))
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.import_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.import_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.import_placeholder)) },
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { show = !show }) {
                    Text(
                        stringResource(if (show) R.string.import_hide else R.string.import_show),
                        fontSize = 12.sp
                    )
                }
            },
            isError = error != null,
            singleLine = false,
            minLines = 2
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.import_storage_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onImport(trimmed) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            enabled = isValid
        ) {
            Text(stringResource(R.string.import_button))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.import_back))
        }
        Spacer(Modifier.height(24.dp))
    }
}
