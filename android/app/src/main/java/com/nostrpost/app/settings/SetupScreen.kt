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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Neutral wording for the detected signer row. Never claims a specific app (e.g. Amber) is
 * installed just because some NIP-55 signer is.
 */
internal fun signerSummary(labels: List<String>): String {
    val label = labels.firstOrNull()?.trim().orEmpty()
    return if (label.isEmpty()) "NIP-55 signer detected" else "NIP-55 signer detected: $label"
}

@Composable
fun SetupScreen(
    hasSigner: Boolean,
    signerLabels: List<String>,
    onConnectSigner: () -> Unit,
    onUseLocal: () -> Unit,
    onInstallAmber: () -> Unit
) {
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
            text = "Sign posts securely",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Keep your Nostr private key separate from this app whenever possible.",
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
                    text = "Recommended",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onConnectSigner,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = hasSigner
                ) {
                    Text(if (hasSigner) "Use Android signer" else "No signer found", fontSize = 16.sp)
                }
                Spacer(Modifier.height(8.dp))
                if (hasSigner) {
                    Text(
                        text = signerSummary(signerLabels),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                } else {
                    Text(
                        text = "Install a NIP-55 signer app (for example Amber) to keep your key isolated.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onInstallAmber, modifier = Modifier.fillMaxWidth()) {
                        Text("Learn about NIP-55 signers")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "A signer keeps your private key outside this app. You’ll approve “sign kind-1” once.",
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
                            text = "For one-tap publishing:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "1. Tap “Use Android signer”\n2. In your signer app enable “Remember my choice” / “Fully trust” for kind 1\n3. Then device unlock is enough — no app PIN each time",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "If your signer still asks for a PIN, open its Settings → Security and use device unlock instead of an app PIN.",
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
            Text("  OR  ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Divider(modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(20.dp))

        OutlinedButton(
            onClick = onUseLocal,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("Store key on this device", fontSize = 15.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Encrypted with Android’s hardware-backed Keystore when available. Less isolated than a dedicated signer.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
    }
}
