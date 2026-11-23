package com.example.input_ds.viewmodel

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import com.example.input_ds.model.*
import com.example.input_ds.data.CommonPhraseUsage
import com.example.input_ds.data.UserDictionary
import org.junit.Assert.*
import org.junit.Test

class InputMethodViewModelTest {
    @Test fun sameDirectionDoesNotSkipTheHighlightedChatCandidate() {
        val runner = InstrumentationRegistry.getInstrumentation()
        val application = runner.targetContext.applicationContext as Application
        runner.runOnMainSync {
            CommonPhraseUsage.init(application)
            UserDictionary.init(application)
            val vm = InputMethodViewModel(application, false)
            vm.setScanMode(ScanMode.AUTO)
            vm.openMode(InputMode.Chat("c1", "妈妈"))
            vm.handleSignal(ControlSignal.BITE)
            assertEquals(InputPhase.PREDICTION, vm.state.value.phase)
            val start = vm.state.value.highlightedPredictionIndex
            try {
                for (step in listOf(1, 1, -1, -1)) {
                    vm.handleSignal(if (step < 0) ControlSignal.LEFT_LOOK else ControlSignal.RIGHT_LOOK)
                    assertEquals(start, vm.state.value.highlightedPredictionIndex)
                    assertEquals(step, vm.state.value.charScanDirection)
                }
                vm.handleSignal(ControlSignal.BITE)
                assertEquals(InputPhase.PREDICTION, vm.state.value.phase)
                assertTrue(vm.state.value.outputText.isNotEmpty())
            } finally {
                vm.deactivate()
            }
        }
    }
}
