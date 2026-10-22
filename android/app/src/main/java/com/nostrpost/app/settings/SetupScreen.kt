package com.nostrpost.app.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nostrpost.app.R
import androidx.compose.ui.unit.sp

/** First installed signer's display label, or empty when none/unknown. */
internal fun firstSignerLabel(labels: List<String>): String =
    labels.firstOrNull()?.trim().orEmpty()

@Composable
fun SetupScreen(
    hasSigner: Boolean,
    signerLabels: List<String>,
    onConnectSigner: () -> Unit,
    onUseLocal: () -> Unit,
    onCreateNew: () -> Unit,
    onInstallAmber: () -> Unit
) {
    val signerLabel = firstSignerLabel(signerLabels)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.setup_title),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.setup_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.setup_recommended),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onConnectSigner,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = hasSigner
                ) {
                    Text(
                        if (hasSigner) stringResource(R.string.setup_use_signer)
                        else stringResource(R.string.setup_no_signer),
                        fontSize = 16.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (hasSigner) {
                    Text(
                        text = if (signerLabel.isEmpty()) {
                            stringResource(R.string.setup_signer_detected_generic)
                        } else {
                            stringResource(R.string.setup_signer_detected, signerLabel)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                } else {
                    Text(
                        text = stringResource(R.string.setup_install_signer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onInstallAmber, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.setup_learn_signers))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.setup_signer_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.setup_one_tap_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.setup_one_tap_steps),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.setup_one_tap_pin),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Divider(modifier = Modifier.weight(1f))
            Text(stringResource(R.string.setup_or), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Divider(modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(20.dp))

        OutlinedButton(
            onClick = onUseLocal,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text(stringResource(R.string.setup_store_key), fontSize = 15.sp)
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onCreateNew,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text(stringResource(R.string.setup_create_identity), fontSize = 15.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.setup_local_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
    }
}
