package com.example.input_ds.wizard

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.input_ds.model.ControlSignal
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.SignalInfo
import org.godotengine.godot.plugin.UsedByGodot
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

internal class InputDsGameBridgePlugin(godot: Godot) : GodotPlugin(godot), WizardGameSignalSink {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val eventSequence = AtomicLong(0L)
    private var resumed = false
    private var mainLoopStarted = false

    override fun getPluginName(): String = PLUGIN_NAME

    override fun getPluginSignals(): Set<SignalInfo> = setOf(
        SignalInfo(CLASSIFICATION_SIGNAL, String::class.java)
    )

    override fun onGodotMainLoopStarted() {
        super.onGodotMainLoopStarted()
        mainLoopStarted = true
        attachWhenReady()
    }

    override fun onMainResume() {
        super.onMainResume()
        resumed = true
        attachWhenReady()
    }

    override fun onMainPause() {
        resumed = false
        WizardGameInputBus.detach(this)
        super.onMainPause()
    }

    override fun onGodotTerminating() {
        WizardGameInputBus.detach(this)
        mainLoopStarted = false
        super.onGodotTerminating()
    }

    override fun onSignal(signal: ControlSignal) {
        val mapped = when (signal) {
            ControlSignal.LEFT_LOOK -> "look_left" to 2
            ControlSignal.RIGHT_LOOK -> "look_right" to 3
            ControlSignal.BITE -> "clench" to 1
            ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> return
        }
        val now = SystemClock.elapsedRealtime()
        val payload = JSONObject().apply {
            put("action", mapped.first)
            put("class_id", mapped.second)
            put("confidence", 1.0)
            put("capture_start_msec", now)
            put("capture_end_msec", now)
            put("result_msec", now)
            put("event_id", "inputds-$now-${eventSequence.incrementAndGet()}")
            put("source", "inputds_bci")
        }.toString()
        mainHandler.post { emitSignal(CLASSIFICATION_SIGNAL, payload) }
    }

    @UsedByGodot
    fun isHostReady(): Boolean = resumed && mainLoopStarted && WizardGameInputBus.hasConsumer()

    @UsedByGodot
    fun exitToHost() {
        runOnHostThread { activity?.finish() }
    }

    private fun attachWhenReady() {
        if (resumed && mainLoopStarted) WizardGameInputBus.attach(this)
    }

    private companion object {
        const val PLUGIN_NAME = "InputDsGameBridge"
        const val CLASSIFICATION_SIGNAL = "classification_received"
    }
}
