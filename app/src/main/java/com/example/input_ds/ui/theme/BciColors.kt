package com.example.input_ds.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class BciColors(
    val bg: Color,
    val panel: Color,
    val panel2: Color,
    val mag: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val dim: Color,
    val focus: Color,
    val focusTint: Color,
    val focusRing: Color,
    val bar: Color,
    val ov: Color,
    val ok: Color,
    val okTint: Color,
    val alarm: Color,
    val alarmTint: Color,
    val onAlarm: Color,
    val careBg: Color,
    val careText: Color,
    val careLine: Color,
    val careBtn: Color,
    val careBtnText: Color,
    val careOff: Color
)

internal val Day = BciColors(
    bg = Color(0xFFF6F3EF),
    panel = Color(0xFFFFFFFF),
    panel2 = Color(0xFFFBF9F6),
    mag = Color(0xFFFCFAF8),
    line = Color(0xFFE5DED5),
    text = Color(0xFF211C18),
    muted = Color(0xFF6A6159),
    dim = Color(0xFFADA59C),
    focus = Color(0xFFD9502A),
    focusTint = Color(0x14D9502A),
    focusRing = Color(0x33D9502A),
    bar = Color(0xFFFCFAF7),
    ov = Color(0xF7F6F3EF),
    ok = Color(0xFF138A5A),
    okTint = Color(0x24138A5A),
    alarm = Color(0xFFC8102E),
    alarmTint = Color(0x1AC8102E),
    onAlarm = Color(0xFFFFFFFF),
    careBg = Color(0xFF1C2230),
    careText = Color(0xFFEDEBE6),
    careLine = Color(0x24FFFFFF),
    careBtn = Color(0xFFEDEBE6),
    careBtnText = Color(0xFF1C2230),
    careOff = Color(0xFF545C6B)
)

internal val Night = BciColors(
    bg = Color(0xFF14110E),
    panel = Color(0xFF1C1915),
    panel2 = Color(0xFF231F1A),
    mag = Color(0xFF17140F),
    line = Color(0xFF312B25),
    text = Color(0xFFEFE8DF),
    muted = Color(0xFFA79D92),
    dim = Color(0xFF625A51),
    focus = Color(0xFFFF8C5E),
    focusTint = Color(0x1FFF8C5E),
    focusRing = Color(0x47FF8C5E),
    bar = Color(0xFF0E0C0A),
    ov = Color(0xF714110E),
    ok = Color(0xFF4CCB8A),
    okTint = Color(0x294CCB8A),
    alarm = Color(0xFFE0243F),
    alarmTint = Color(0x29E0243F),
    onAlarm = Color(0xFFFFFFFF),
    careBg = Color(0xFFECEAE4),
    careText = Color(0xFF1B1E24),
    careLine = Color(0x1F000000),
    careBtn = Color(0xFF1B1E24),
    careBtnText = Color(0xFFECEAE4),
    careOff = Color(0xFFA3A8B0)
)

val LocalBciColors = staticCompositionLocalOf { Day }

@Composable
private fun transition(target: Color) = animateColorAsState(target, tween(300), label = "displayMode").value

@Composable
internal fun BciColors.animated() = BciColors(
    bg = transition(bg),
    panel = transition(panel),
    panel2 = transition(panel2),
    mag = transition(mag),
    line = transition(line),
    text = transition(text),
    muted = transition(muted),
    dim = transition(dim),
    focus = transition(focus),
    focusTint = transition(focusTint),
    focusRing = transition(focusRing),
    bar = transition(bar),
    ov = transition(ov),
    ok = transition(ok),
    okTint = transition(okTint),
    alarm = transition(alarm),
    alarmTint = transition(alarmTint),
    onAlarm = transition(onAlarm),
    careBg = transition(careBg),
    careText = transition(careText),
    careLine = transition(careLine),
    careBtn = transition(careBtn),
    careBtnText = transition(careBtnText),
    careOff = transition(careOff)
)
