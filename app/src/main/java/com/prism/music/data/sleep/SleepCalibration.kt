package com.prism.music.data.sleep

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The score's weights, fitted from the listener's own judgments when there are enough of them (1.9.2).
 *
 * Until then the transparent defaults from [SleepWeights] are used: the model is a bounded logistic over interpretable
 * features, not a stack of hard caps. Fitting is a small deterministic gradient descent on those stored features, so no
 * audio has to be decoded again and the same labels always produce the same weights.
 */
class SleepCalibration(context: Context) {
    private val prefs = context.getSharedPreferences("sleep_score_calibration", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _weights = MutableStateFlow(read())
    val weights: StateFlow<SleepWeights> = _weights

    private fun read(): SleepWeights =
        prefs.getString("weights", null)?.let { runCatching { json.decodeFromString<SleepWeights>(it) }.getOrNull() }
            ?: SleepWeights()

    @Synchronized
    fun save(weights: SleepWeights) {
        prefs.edit().putString("weights", json.encodeToString(weights)).apply()
        _weights.value = weights
    }

    @Synchronized
    fun reset() {
        prefs.edit().clear().apply()
        _weights.value = SleepWeights()
    }

    /**
     * Fits bias and the eight disturbance weights to (features, judged suitability) pairs. Monotonicity is kept: every
     * weight stays non-negative, so more disturbance can never raise a score. L2 pulls toward the transparent defaults
     * so a handful of labels can't produce an extreme model.
     */
    fun fit(samples: List<Pair<SleepFeatures, Double>>): SleepWeights {
        if (samples.size < 6) return weights.value
        var bias = weights.value.bias
        val w = weights.value.weights.copyOf()
        val lr = 0.5 / samples.size
        repeat(400) { _ ->
            for ((features, target) in samples) {
                val f = features.asArray()
                var logit = bias
                for (i in f.indices) logit -= w[i] * f[i]
                val p = 1.0 / (1.0 + kotlin.math.exp(-logit))
                val err = p - target
                bias -= lr * err
                for (i in f.indices) w[i] += lr * err * f[i]
                // Keep the model sane and monotonic.
                bias = bias.coerceIn(-1.0, 4.5)
                for (i in f.indices) w[i] = w[i].coerceIn(0.0, 3.0)
            }
            // A gentle pull toward the defaults, not toward zero: the defaults already encode the design.
            for (i in w.indices) {
                w[i] = (w[i] - 0.002 * (w[i] - SleepWeights.DEFAULT_WEIGHTS[i])).coerceIn(0.0, 3.0)
            }
        }
        return SleepWeights(bias, w, samples.size)
    }

    /** Whether re-fitting would change the held weights enough to be worth recalculating the library. */
    fun changed(old: SleepWeights, new: SleepWeights): Boolean =
        kotlin.math.abs(old.bias - new.bias) > 0.05 || old.weights.indices.any { kotlin.math.abs(old.weights[it] - new.weights[it]) > 0.05 }
}
