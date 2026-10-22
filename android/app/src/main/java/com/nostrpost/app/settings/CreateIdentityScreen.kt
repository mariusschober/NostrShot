package com.nostrpost.app.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nostrpost.app.nostr.Bech32
import com.nostrpost.app.signing.IdentityGenerator

/**
 * Generates a new identity and shows the nsec exactly once, followed by a full-npub
 * confirmation before anything is saved.
 */
@Composable
fun CreateIdentityScreen(
    onCreate: (secret: ByteArray, pubkeyHex: String) -> Unit,
    onBack: () -> Unit
) {
    val generated = remember { IdentityGenerator.generate() }
    val nsec = remember { Bech32.encode("nsec", generated.secret) }
    val npub = remember {
        try { Bech32.toNpub(generated.pubkeyHex) } catch (_: Exception) { generated.pubkeyHex }
    }
    var revealed by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf(0) }

    // Zero the in-memory secret when leaving the screen (the caller also zeroes after storing).
    DisposableEffect(Unit) {
        onDispose { generated.secret.fill(0) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (step == 0) "Back up your new key" else "Confirm your identity",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))

        if (step == 0) {
            Text(
                "This secret key (nsec) is shown only once. Write it down or store it in a password manager. Anyone who has it can post as you; nobody can recover it for you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Private key (nsec)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(
                            text = if (revealed) nsec else "nsec1" + "•".repeat(24),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { revealed = !revealed }) {
                        Text(if (revealed) "Hide secret" else "Show secret")
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { step = 1 },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("I stored my key") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        } else {
            Text(
                "Check that this public key (npub) is the identity you will post with. It is safe to share.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        text = npub,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onCreate(generated.secret, generated.pubkeyHex) },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("Create identity") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { step = 0 }, modifier = Modifier.fillMaxWidth()) { Text("Back to key") }
        }
        Spacer(Modifier.height(24.dp))
    }
}
