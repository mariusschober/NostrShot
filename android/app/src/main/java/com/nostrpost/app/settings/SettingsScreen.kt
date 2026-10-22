package com.nostrpost.app.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import com.nostrpost.app.R
import com.nostrpost.app.nostr.Bech32
import com.nostrpost.app.nostr.RelayConfig
import com.nostrpost.app.nostr.RelayHealthStore
import com.nostrpost.app.signing.Nip55
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val normalizedRelayInput = RelayConfig.normalizeOrNull(addRelayText)
    val relayInputError = when {
        addRelayText.isBlank() -> null
        normalizedRelayInput == null -> stringResource(R.string.settings_relay_invalid)
        RelayConfig.isDuplicate(addRelayText, relays) -> stringResource(R.string.settings_relay_duplicate)
        else -> null
    }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var showChangeConfirm by remember { mutableStateOf(false) }
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
                TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(64.dp)) // balance
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.settings_identity), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    if (signerMode == "nip55") {
                        Text(stringResource(R.string.settings_signing), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.settings_android_signer), style = MaterialTheme.typography.bodyLarge)
                        if (signerPackage != null) Text(signerPackage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (signerMode == "local") {
                        Text(stringResource(R.string.settings_signing), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.settings_stored_securely), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(
                                R.string.settings_protection,
                                protectionLevel ?: stringResource(R.string.settings_protection_unknown)
                            ),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(stringResource(R.string.settings_not_setup), color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(12.dp))
                    if (npub != null) {
                        Text(truncated, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { onCopyNpub(npub) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.settings_copy_npub))
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
                                        val ok = withContext(Dispatchers.IO) {
                                            Nip55.isBackgroundAvailable(context, pkg, hex)
                                        }
                                        backgroundCheck = context.getString(
                                            if (ok) R.string.settings_one_tap_ready
                                            else R.string.settings_one_tap_pin
                                        )
                                        isChecking = false
                                    }
                                } else {
                                    backgroundCheck = context.getString(R.string.settings_no_signer_configured)
                                    isChecking = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(
                                    if (isChecking) R.string.settings_checking
                                    else R.string.settings_test_one_tap
                                )
                            )
                        }
                        if (backgroundCheck != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(backgroundCheck!!, style = MaterialTheme.typography.bodySmall, color = if (backgroundCheck!!.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            if (backgroundCheck!!.startsWith("✗")) {
                                Spacer(Modifier.height(6.dp))
                                Text(stringResource(R.string.settings_fix_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                ) { Text(stringResource(R.string.settings_open_signer)) }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { showChangeConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.settings_change_signer))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.settings_device_unlock_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.7f))
                    } else if (signerMode == "local") {
                        OutlinedButton(onClick = { showRemoveConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.settings_remove_key))
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.settings_relays), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        pluralStringResource(R.plurals.settings_relay_count, relays.size, relays.size),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    relays.forEach { relay ->
                        val health = RelayHealthStore.forRelay(RelayConfig.normalize(relay))
                            ?: RelayHealthStore.forRelay(relay)
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(relay, fontSize = 13.sp)
                                if (health != null) {
                                    Text(
                                        health.statusText(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (health.lastError != null) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            TextButton(onClick = { onRemoveRelay(relay) }) {
                                Text(stringResource(R.string.action_remove), fontSize = 12.sp)
                            }
                        }
                        Divider()
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = addRelayText,
                        onValueChange = { addRelayText = it },
                        placeholder = { Text(stringResource(R.string.settings_relay_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = relayInputError != null,
                        supportingText = {
                            if (relayInputError != null) Text(relayInputError)
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val v = normalizedRelayInput ?: return@Button
                            onAddRelay(v)
                            addRelayText = ""
                        }, enabled = normalizedRelayInput != null && relayInputError == null) {
                            Text(stringResource(R.string.action_add))
                        }
                        OutlinedButton(onClick = onResetRelays) {
                            Text(stringResource(R.string.settings_reset_relays))
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.settings_about_version), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.settings_about_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text(stringResource(R.string.settings_remove_title)) },
            text = { Text(stringResource(R.string.settings_remove_text)) },
            confirmButton = {
                Button(onClick = { showRemoveConfirm = false; onRemoveKey() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.settings_remove_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { showRemoveConfirm = false }) {
                        Text(stringResource(R.string.action_cancel))
                    } }
        )
    }

    if (showChangeConfirm) {
        AlertDialog(
            onDismissRequest = { showChangeConfirm = false },
            title = { Text(stringResource(R.string.settings_change_title)) },
            text = { Text(stringResource(R.string.settings_change_text)) },
            confirmButton = {
                Button(onClick = { showChangeConfirm = false; onChangeSigner() }) {
                    Text(stringResource(R.string.settings_change_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { showChangeConfirm = false }) {
                        Text(stringResource(R.string.action_cancel))
                    } }
        )
    }
}
