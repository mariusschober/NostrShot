package com.nostrpost.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.nostrpost.app.composer.ComposerScreen
import com.nostrpost.app.composer.ComposerViewModel
import com.nostrpost.app.nostr.Bech32
import com.nostrpost.app.nostr.Hex
import com.nostrpost.app.nostr.NostrCrypto
import com.nostrpost.app.nostr.RelayPublisher
import com.nostrpost.app.security.AndroidKeystoreSecretStore
import com.nostrpost.app.settings.ImportScreen
import com.nostrpost.app.settings.SettingsScreen
import com.nostrpost.app.settings.SetupScreen
import com.nostrpost.app.signing.Nip55
import com.nostrpost.app.signing.SignerRepository
import com.nostrpost.app.storage.Preferences
import com.nostrpost.app.ui.NostrPostTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Preferences
    private lateinit var secretStore: AndroidKeystoreSecretStore
    private lateinit var signerRepo: SignerRepository
    private lateinit var viewModel: ComposerViewModel
    private var pendingQuickCompose: Boolean = false

    // For get_public_key
    private var awaitingSignerSetup = false
    private val getPubKeyLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        lifecycleScope.launch {
            if (result.resultCode == RESULT_OK) {
                val pair = signerRepo.parseGetPublicKeyResult(result.data)
                if (pair != null) {
                    signerRepo.storeNip55Identity(pair.first, pair.second)
                    Toast.makeText(this@MainActivity, "Signer connected", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to get public key from signer", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this@MainActivity, "Signer connection cancelled", Toast.LENGTH_SHORT).show()
            }
            awaitingSignerSetup = false
        }
    }

    // For sign_event interactive fallback: keep the exact unsigned event we asked to sign.
    private var pendingSignEvent: com.nostrpost.app.nostr.UnsignedEvent? = null
    private val signEventLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val expected = pendingSignEvent
        if (expected != null) {
            viewModel.onSignerActivityResult(result.data, result.resultCode, expected)
            pendingSignEvent = null
        }
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash: keep <120ms
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { false }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Predictive back already enabled via manifest; ensure window callback
        // Share target pre-process: only for a fresh launch. On rotation the activity is
        // recreated with the same launch Intent, which must not be applied a second time.
        if (savedInstanceState == null) {
            handleShareIntent(intent)
        }

        pendingQuickCompose = intent.getBooleanExtra("quick_compose", false) ||
                intent.getStringExtra("quick_compose") == "1" ||
                intent.data?.toString()?.contains("quick_compose") == true

        prefs = Preferences(this)
        secretStore = AndroidKeystoreSecretStore(this)
        signerRepo = SignerRepository(this, prefs, secretStore)
        viewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ComposerViewModel(prefs, signerRepo, RelayPublisher()) as T
            }
        })[ComposerViewModel::class.java]

        // Speculative signer pre-warm (no UI block)
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val available = Nip55.isSignerAvailable(this@MainActivity)
                if (available) {
                    // Warm PackageManager query cache
                    Nip55.getSignerPackages(this@MainActivity)
                    android.util.Log.d("MainActivity", "Signer pre-warm done")
                }
            } catch (_: Exception) {}
        }

        // Collect effects
        lifecycleScope.launch {
            viewModel.effect.collect { effect ->
                when (effect) {
                    is com.nostrpost.app.composer.ComposerEffect.LaunchSignerIntent -> {
                        pendingSignEvent = effect.unsigned
                        try {
                            signEventLauncher.launch(effect.intent)
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Unable to launch signer: ${e.message}", Toast.LENGTH_LONG).show()
                            viewModel.clearEffect()
                        }
                    }
                    is com.nostrpost.app.composer.ComposerEffect.CloseApp -> {
                        // brief success then close
                        kotlinx.coroutines.delay(300)
                        finishAndRemoveTask()
                    }
                    else -> {}
                }
                if (effect != null) viewModel.clearEffect()
            }
        }

        setContent {
            NostrPostTheme {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        pendingQuickCompose = intent.getBooleanExtra("quick_compose", false) || intent.getStringExtra("quick_compose") == "1"
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
            if (!shared.isNullOrBlank()) {
                // The ViewModel decides whether to apply immediately or queue until setup.
                viewModel.onSharedText(shared)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        lifecycleScope.launch { viewModel.saveDraftImmediately() }
    }

    @Composable
    private fun AppRoot() {
        var setupNeeded by remember { mutableStateOf<Boolean?>(null) }
        var nav by remember { mutableStateOf("composer") } // composer, setup, import, settings
        var importError by remember { mutableStateOf<String?>(null) }
        var composeState by remember { mutableStateOf(viewModel.state.value) }
        val context = LocalContext.current
        val clipboard = LocalClipboardManager.current

        // Observe viewModel state
        LaunchedEffect(Unit) {
            viewModel.state.collect { composeState = it }
            // also update setupNeeded
            prefs.isSetupFlow.collect { isSetup ->
                setupNeeded = !isSetup
                // if setup needed and nav is composer, switch to setup
                if (isSetup.not() && nav == "composer") {
                    // stay? Actually initial will be setup
                }
            }
        }
        // Initial check: never leave the app on a spinner if storage cannot be read.
        LaunchedEffect(Unit) {
            val isSetup = try {
                prefs.isSetupFlow.first()
            } catch (_: Exception) {
                false
            }
            setupNeeded = !isSetup
            if (!isSetup) nav = "setup"
        }
        LaunchedEffect(Unit) {
            Preferences.preferencesResetEvents.collect {
                Toast.makeText(
                    context,
                    "Stored settings were reset because they could not be read",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        when {
            setupNeeded == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            nav == "setup" -> {
                val hasSigner = remember { Nip55.isSignerAvailable(context) }
                val packages = remember { Nip55.getSignerPackages(context) }
                SetupScreen(
                    hasSigner = hasSigner,
                    signerPackages = packages,
                    onConnectSigner = {
                        val perms = Nip55.permissionsForKind1()
                        val intent = Nip55.createGetPublicKeyIntent(perms)
                        try {
                            getPubKeyLauncher.launch(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "No signer app found", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onUseLocal = { nav = "import" },
                    onInstallAmber = {
                        try {
                            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/greenart7c3/Amber"))
                            startActivity(i)
                        } catch (_: Exception) {}
                    }
                )
                // If setup completed externally, navigate
                LaunchedEffect(composeState.isSetup) {
                    if (composeState.isSetup) nav = "composer"
                }
            }
            nav == "import" -> {
                // Mark window secure during import
                DisposableEffect(Unit) {
                    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                }
                ImportScreen(
                    onImport = { input ->
                        lifecycleScope.launch {
                            importError = null
                            try {
                                val hex: String
                                val pubHex: String
                                if (input.startsWith("nsec1")) {
                                    val bytes = Bech32.decodeNsec(input)
                                    hex = Hex.encode(bytes)
                                    pubHex = NostrCrypto.pubkeyFromPrivBytes(bytes)
                                    bytes.fill(0)
                                } else if (input.matches(Regex("[0-9a-fA-F]{64}"))) {
                                    hex = input.lowercase()
                                    if (!NostrCrypto.isValidPrivHex(hex)) {
                                        importError = "Invalid secret key"
                                        return@launch
                                    }
                                    val bytes = Hex.decode(hex)
                                    pubHex = NostrCrypto.pubkeyFromPrivBytes(bytes)
                                    bytes.fill(0)
                                } else {
                                    importError = "Enter nsec1… or 64-char hex"
                                    return@launch
                                }
                                // Store; the repository records the real protection level
                                // returned by the Keystore after the wrapping key exists.
                                signerRepo.storeLocalIdentity(hex, pubHex)
                                // clear input
                                Toast.makeText(context, "Key imported securely", Toast.LENGTH_SHORT).show()
                                nav = "composer"
                                hex.toCharArray().fill('0') // not real clear but ok
                            } catch (e: Exception) {
                                importError = e.message ?: "Import failed"
                            }
                        }
                    },
                    onBack = { nav = "setup" },
                    error = importError
                )
            }
            nav == "settings" -> {
                val relays by prefs.relaysFlow.collectAsState(initial = com.nostrpost.app.nostr.RelayConfig.DEFAULT_RELAYS)
                val pubkey by prefs.pubkeyFlow.collectAsState(initial = null)
                val mode by prefs.signerModeFlow.collectAsState(initial = null)
                val pkg by prefs.signerPackageFlow.collectAsState(initial = null)
                val prot by prefs.keyProtectionFlow.collectAsState(initial = null)

                // Resolve a missing protection level once (e.g. old installs) without a
                // composable side-effect that could render "checking…" forever.
                var resolvedProtection by remember(mode, prot) { mutableStateOf(prot) }
                LaunchedEffect(mode, prot) {
                    resolvedProtection = if (prot != null) {
                        prot
                    } else if (mode == "local") {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            secretStore.getProtectionLevel()
                        } ?: "Unknown"
                    } else {
                        null
                    }
                }

                SettingsScreen(
                    pubkeyHex = pubkey,
                    signerMode = mode,
                    signerPackage = pkg,
                    protectionLevel = resolvedProtection,
                    relays = relays,
                    onCopyNpub = { npub ->
                        clipboard.setText(AnnotatedString(npub))
                        Toast.makeText(context, "Copied npub", Toast.LENGTH_SHORT).show()
                    },
                    onChangeSigner = {
                        lifecycleScope.launch {
                            signerRepo.clearIdentity()
                            nav = "setup"
                        }
                    },
                    onRemoveKey = {
                        lifecycleScope.launch {
                            signerRepo.clearIdentity()
                            nav = "setup"
                            Toast.makeText(context, "Key removed", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onAddRelay = { url ->
                        lifecycleScope.launch { prefs.setRelays(relays + url) }
                    },
                    onRemoveRelay = { url ->
                        lifecycleScope.launch { prefs.setRelays(relays.filterNot { it == url }) }
                    },
                    onResetRelays = {
                        lifecycleScope.launch { prefs.setRelays(com.nostrpost.app.nostr.RelayConfig.DEFAULT_RELAYS) }
                    },
                    onBack = { nav = "composer" }
                )
            }
            else -> {
                // Composer - stable without voice
                ComposerScreen(
                    state = composeState,
                    onContentChange = { viewModel.onContentChange(it) },
                    onPublish = { viewModel.onPublishClicked() },
                    onSettingsClick = { nav = "settings" },
                    onRetry = { viewModel.onRetry() },
                    onDismissError = { viewModel.dismissError() },
                    onOpenSettingsFromError = { nav = "settings" }
                )
                // If setup incomplete but we somehow reached composer, redirect
                LaunchedEffect(composeState.isSetup) {
                    if (!composeState.isSetup) nav = "setup"
                }
            }
        }
    }
}
