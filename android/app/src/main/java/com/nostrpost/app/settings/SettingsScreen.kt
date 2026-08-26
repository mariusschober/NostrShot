package com.nostrpost.app.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import com.nostrpost.app.nostr.Bech32
import com.nostrpost.app.signing.Nip55
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    pubkeyHex: String?,
    signerMode: String?,
    signerPackage: String?,
    protectionLevel: String?,
    relays: List<String>,
    onCopyNpub: (String) -> Unit = {},
    onChangeSigner: () -> Unit,
    onRemoveKey: () -> Unit,
    onAddRelay: (String) -> Unit,
    onRemoveRelay: (String) -> Unit,
    onResetRelays: () -> Unit,
    onBack: () -> Unit
) {
    var addRelayText by remember { mutableStateOf("") }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var backgroundCheck by remember { mutableStateOf<String?>(null) }
    var isChecking by remember { mutableStateOf(false) }

    val npub = remember(pubkeyHex) {
        try { if (pubkeyHex != null) Bech32.toNpub(pubkeyHex) else null } catch (_: Exception) { null }
    }
    val truncated = remember(npub) { npub?.let { it.take(9) + "…" + it.takeLast(4) } ?: "..." }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Spacer(Modifier.weight(1f))
                Text("Settings", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(64.dp)) // balance
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Identity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    if (signerMode == "nip55") {
                        Text("Signing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text("Android signer", style = MaterialTheme.typography.bodyLarge)
                        if (signerPackage != null) Text(signerPackage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (signerMode == "local") {
                        Text("Signing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text("Stored securely on this device", style = MaterialTheme.typography.bodyLarge)
                        Text("Protection: ${protectionLevel ?: "checking…"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("Not set up", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(12.dp))
                    if (npub != null) {
                        Text(truncated, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { onCopyNpub(npub) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Copy npub")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (signerMode == "nip55") {
                        // Background signing diagnostic
                        OutlinedButton(
                            onClick = {
                                isChecking = true
                                backgroundCheck = null
                                val pkg = signerPackage
                                val hex = pubkeyHex
                                if (pkg != null && hex != null) {
                                    scope.launch {
                                        val ok = Nip55.isBackgroundAvailable(context, pkg, hex)
                                        backgroundCheck = if (ok) "✓ One-tap ready — device unlock only" else "✗ Still asks PIN — tap Fix"
                                        isChecking = false
                                    }
                                } else {
                                    backgroundCheck = "No signer configured"
                                    isChecking = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isChecking) "Checking…" else "Test one-tap")
                        }
                        if (backgroundCheck != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(backgroundCheck!!, style = MaterialTheme.typography.bodySmall, color = if (backgroundCheck!!.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            if (backgroundCheck!!.startsWith("✗")) {
                                Spacer(Modifier.height(6.dp))
                                Text("Fix: In Amber, delete Nostr Post app → reconnect → check ☑ Remember → fully trust kind 1. Then set Amber Settings → Security → unlock with device instead of Amber PIN.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
                                            intent.`package` = signerPackage
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            try { context.startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)) } catch (_: Exception) {}
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Open Amber") }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onChangeSigner, modifier = Modifier.fillMaxWidth()) {
                            Text("Change signer")
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("Device unlock only: after one approval with “Remember”, publishing uses background signing — like other Nostr apps.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.7f))
                    } else if (signerMode == "local") {
                        OutlinedButton(onClick = { showRemoveConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Remove key")
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Relays", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("${relays.size} relays — at least one OK counts as published.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    relays.forEach { relay ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(relay, modifier = Modifier.weight(1f), fontSize = 13.sp)
                            TextButton(onClick = { onRemoveRelay(relay) }) { Text("Remove", fontSize = 12.sp) }
                        }
                        Divider()
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = addRelayText,
                        onValueChange = { addRelayText = it },
                        placeholder = { Text("wss://…") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val v = addRelayText.trim()
                            if (v.startsWith("wss://") || v.startsWith("ws://")) {
                                onAddRelay(v)
                                addRelayText = ""
                            }
                        }, enabled = addRelayText.trim().startsWith("wss://") || addRelayText.trim().startsWith("ws://")) {
                            Text("Add")
                        }
                        OutlinedButton(onClick = onResetRelays) { Text("Reset defaults") }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("Nostr Post 1.0.0 — tap → type → publish → gone.", style = MaterialTheme.typography.bodyMedium)
                    Text("No feed. No analytics. Local signing via NIP-55 or hardware-backed Keystore.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("Remove key?") },
            text = { Text("Your private key will be deleted from this device. You must have a backup elsewhere.") },
            confirmButton = {
                Button(onClick = { showRemoveConfirm = false; onRemoveKey() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text("Remove")
                }
            },
            dismissButton = { TextButton(onClick = { showRemoveConfirm = false }) { Text("Cancel") } }
        )
    }
}
