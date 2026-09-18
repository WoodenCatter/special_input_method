package com.example.input_ds.wizard

import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.ui.components.FloatingControlBall
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
                    FloatingControlBall(
                        visible = true,
                        onLeftLook = { WizardGameInputBus.dispatch(ControlSignal.LEFT_LOOK) },
                        onRightLook = { WizardGameInputBus.dispatch(ControlSignal.RIGHT_LOOK) },
                        onBite = { WizardGameInputBus.dispatch(ControlSignal.BITE) }
                    )
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
        }

    override fun getHostPlugins(godot: Godot): MutableSet<GodotPlugin> =
        super.getHostPlugins(godot).toMutableSet().apply {
            add(InputDsGameBridgePlugin(godot))
        }
}
