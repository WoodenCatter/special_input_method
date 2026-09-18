package com.example.input_ds.bci

/** The one preprocessing operation used by plotting, inference and training metadata. */
enum class EegPreprocessStep(
    val displayName: String,
    val shortName: String
) {
    BANDPASS_1_45_HZ("1–45 Hz 带通", "1–45Hz");

    companion object {
        val DEFAULT: Set<EegPreprocessStep> = setOf(BANDPASS_1_45_HZ)

        fun describe(@Suppress("UNUSED_PARAMETER") steps: Set<EegPreprocessStep>): String =
            BANDPASS_1_45_HZ.shortName
    }
}
