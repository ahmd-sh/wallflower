package sh.ahmd.wallflower

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.ahmd.wallflower.notify.Notices
import sh.ahmd.wallflower.ui.Haptics
import sh.ahmd.wallflower.ui.LocalHaptics
import sh.ahmd.wallflower.ui.WallflowerRoot
import sh.ahmd.wallflower.ui.WallflowerTheme
import sh.ahmd.wallflower.ui.colorsOf
import sh.ahmd.wallflower.ui.files.LocalViewer

class MainActivity : ComponentActivity() {
    private companion object {
        const val ASKED_TO_NOTIFY = "asked_to_notify"
    }

    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val look by model.settings.look.flow.collectAsStateWithLifecycle()
            val scheme by model.settings.scheme.flow.collectAsStateWithLifecycle()
            val colors = colorsOf(scheme)
            LaunchedEffect(colors.light) { bars(colors.light) }
            val view = LocalView.current
            val haptics = remember(view) { Haptics(view, model.settings.haptics.flow) }
            CompositionLocalProvider(LocalHaptics provides haptics, LocalViewer provides model.viewer) {
                WallflowerTheme(look, colors) {
                    WallflowerRoot(model, ::unlock) {
                        model.disconnect()
                        finish()
                    }
                }
            }
        }
        opened(intent)
    }

    /** The system bars' icons dark over a [light] theme, light over a dark one. */
    private fun bars(light: Boolean) {
        val style = if (light) SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT) else SystemBarStyle.dark(Color.TRANSPARENT)
        enableEdgeToEdge(style, style)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        opened(intent)
    }

    /** From a notification: the pane it's about. */
    private fun opened(intent: Intent) {
        intent.getStringExtra(Notices.EXTRA_PANE)?.let { model.opening.value = it }
    }

    override fun onStart() {
        super.onStart()
        connection.away(false)
        askToNotify()
    }

    override fun onStop() {
        super.onStop()
        connection.away(true)
    }

    private val notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /** Once, while connected: notifications are how the Mac's programs reach the user while they're away. */
    private fun askToNotify() {
        val prefs = getSharedPreferences("wallflower", MODE_PRIVATE)
        if (connection.session.value == null || prefs.getBoolean(ASKED_TO_NOTIFY, false)) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        prefs.edit().putBoolean(ASKED_TO_NOTIFY, true).apply()
        notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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
