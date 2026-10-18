package sh.ahmd.wallflower

import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.ahmd.wallflower.ui.Haptics
import sh.ahmd.wallflower.ui.LocalHaptics
import sh.ahmd.wallflower.ui.WallflowerRoot
import sh.ahmd.wallflower.ui.WallflowerTheme

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val look by model.settings.look.flow.collectAsStateWithLifecycle()
            val view = LocalView.current
            val haptics = remember(view) { Haptics(view, model.settings.haptics.flow) }
            CompositionLocalProvider(LocalHaptics provides haptics) {
                WallflowerTheme(look) { WallflowerRoot(model, ::unlock, onExit = ::finish) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        model.onForeground()
    }

    /** Fingerprint or screen lock; unlocks the key for HardwareKey.AUTH_SECONDS. */
    private fun unlock(onDone: (Boolean) -> Unit) {
        BiometricPrompt.Builder(this)
            .setTitle("Unlock wallflower")
            .setSubtitle("Your SSH key lives in this phone's secure hardware")
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
            .build()
            .authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onDone(true)
                override fun onAuthenticationError(code: Int, message: CharSequence) = onDone(false)
            })
    }
}
