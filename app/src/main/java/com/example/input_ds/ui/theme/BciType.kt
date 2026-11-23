package com.example.input_ds.ui.theme

import android.os.Build
import androidx.compose.ui.text.font.*
import com.example.input_ds.R

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
object BciType {
    private fun family(resource: Int, vararg weights: Int): FontFamily {
        if (Build.VERSION.SDK_INT < 26) return FontFamily.SansSerif
        return FontFamily(weights.map {
            Font(resource, FontWeight(it), variationSettings = FontVariation.Settings(FontVariation.weight(it)))
        })
    }
    val latin = family(R.font.archivo, 500, 700, 800)
    val chinese = family(R.font.noto_sans_sc, 400, 500, 700, 900)
}
