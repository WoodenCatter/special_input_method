package com.example.input_ds.wizard

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.ui.components.FloatingControlBall
import com.example.input_ds.ui.components.FloatingEegSignalSource
import com.example.input_ds.ui.components.FloatingEegSignalWindow
import com.example.input_ds.ui.theme.InputDSTheme
import org.godotengine.godot.Godot
import org.godotengine.godot.GodotActivity
import org.godotengine.godot.plugin.GodotPlugin

/** Hosts the Godot game inside the same APK and process as the inputds application. */
class WizardGameActivity : GodotActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val floatingControls = ComposeView(this).apply {
            setContent {
                InputDSTheme {
                    val signalManager by FloatingEegSignalSource.manager.collectAsState()
                    Box(Modifier.fillMaxSize()) {
                        signalManager?.let { manager ->
                            FloatingEegSignalWindow(bleManager = manager, visible = true)
                        }
                        FloatingControlBall(
                            visible = true,
                            onLeftLook = { WizardGameInputBus.dispatch(ControlSignal.LEFT_LOOK) },
                            onRightLook = { WizardGameInputBus.dispatch(ControlSignal.RIGHT_LOOK) },
                            onBite = { WizardGameInputBus.dispatch(ControlSignal.BITE) }
                        )
                    }
                }
            }
        }
        addContentView(
            floatingControls,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    override fun getCommandLine(): MutableList<String> =
        super.getCommandLine().toMutableList().apply {
            add("--main-pack")
            add("res://wizard_game.pck")
            if (shouldUseCompatibilityRenderer()) {
                add("--rendering-method")
                add("gl_compatibility")
                add("--rendering-driver")
                add("opengl3")
            }
        }

    private fun shouldUseCompatibilityRenderer(): Boolean {
        val hasVulkan11 = packageManager.hasSystemFeature(
            PackageManager.FEATURE_VULKAN_HARDWARE_VERSION,
            VULKAN_1_1_VERSION
        )
        return !hasVulkan11 || isEmulator()
    }

    private companion object {
        const val VULKAN_1_1_VERSION = 0x401000

        fun isEmulator(): Boolean {
            val fingerprint = Build.FINGERPRINT.lowercase()
            val model = Build.MODEL.lowercase()
            val product = Build.PRODUCT.lowercase()
            val hardware = Build.HARDWARE.lowercase()
            val brand = Build.BRAND.lowercase()
            val device = Build.DEVICE.lowercase()
            return fingerprint.startsWith("generic") ||
                fingerprint.contains("emulator") ||
                model.contains("emulator") ||
                model.contains("sdk_gphone") ||
                model.contains("android sdk built for") ||
                product.contains("sdk") ||
                product.contains("emulator") ||
                hardware.contains("goldfish") ||
                hardware.contains("ranchu") ||
                (brand.startsWith("generic") && device.startsWith("generic"))
        }
    }

    override fun getHostPlugins(godot: Godot): MutableSet<GodotPlugin> =
        super.getHostPlugins(godot).toMutableSet().apply {
            add(InputDsGameBridgePlugin(godot))
        }
}
