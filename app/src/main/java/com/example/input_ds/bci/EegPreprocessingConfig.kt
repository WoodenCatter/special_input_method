package com.example.input_ds.bci

/**
 * A session-level preprocessing chain shared by the live plot and inference.
 *
 * Steps always run in enum order so selecting multiple filters is deterministic:
 * notch -> broad band-pass -> model band-pass -> mean removal -> Z-score.
 */
enum class EegPreprocessStep(
    val displayName: String,
    val shortName: String
) {
    NOTCH_50_HZ("50 Hz 陷波", "50Hz陷波"),
    BANDPASS_0_1_100_HZ("0.1–100 Hz 带通", "0.1–100Hz"),
    BANDPASS_0_1_40_HZ("0.1–40 Hz 带通", "0.1–40Hz"),
    BANDPASS_1_45_HZ("1–45 Hz 带通", "1–45Hz"),
    REMOVE_MEAN("去均值", "去均值"),
    Z_SCORE("Z-score", "Z-score");

    companion object {
        val DEFAULT: Set<EegPreprocessStep> = setOf(BANDPASS_1_45_HZ)

        fun describe(steps: Set<EegPreprocessStep>): String =
            if (steps.isEmpty()) {
                "原始信号"
            } else {
                entries.filter { it in steps }.joinToString(" + ") { it.shortName }
            }
    }
}
