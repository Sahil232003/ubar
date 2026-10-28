package com.uber.edge.domain.ai

import com.uber.edge.domain.port.EtaModel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Feature vector for the on-device ETA model (PRD Section 8.2).
 */
data class EtaFeatures(
    val meanSpeed30s: Double,
    val stdSpeed30s: Double,
    val meanSpeed60s: Double,
    val stdSpeed60s: Double,
    val meanSpeed120s: Double,
    val stdSpeed120s: Double,
    val remainingDistanceM: Int,
    val serverEtaSec: Int,
    val serverEtaAgeSec: Int,
    val stopsCountOver20s: Int,
    val hourSin: Double,
    val hourCos: Double,
    val dayOfWeek: Int
)

/**
 * Stage 2 Edge-AI: On-Device ETA Correction Engine
 * Predicts etaCorrectionSec and confidence based on recent driving conditions.
 */
class EtaCorrectionEngine : EtaModel {

    override suspend fun predictCorrection(
        speedHistoryMps: List<Double>,
        remainingDistanceM: Int,
        serverEtaSec: Int,
        serverEtaAgeSec: Int,
        stopCountOver20s: Int
    ): EtaModel.Prediction {
        if (speedHistoryMps.isEmpty() || remainingDistanceM <= 0 || serverEtaSec <= 0) {
            return EtaModel.Prediction(etaCorrectionSec = 0, confidence = 0.0)
        }

        val features = extractFeatures(
            speedHistory = speedHistoryMps,
            remainingDistanceM = remainingDistanceM,
            serverEtaSec = serverEtaSec,
            serverEtaAgeSec = serverEtaAgeSec,
            stopCountOver20s = stopCountOver20s,
            hourOfDay = 17, // default afternoon peak or derived from clock
            dayOfWeek = 3
        )

        return runInference(features)
    }

    fun extractFeatures(
        speedHistory: List<Double>,
        remainingDistanceM: Int,
        serverEtaSec: Int,
        serverEtaAgeSec: Int,
        stopCountOver20s: Int,
        hourOfDay: Int,
        dayOfWeek: Int
    ): EtaFeatures {
        // Samples are roughly 1 per second
        val n = speedHistory.size
        val s30 = speedHistory.takeLast(min(30, n))
        val s60 = speedHistory.takeLast(min(60, n))
        val s120 = speedHistory.takeLast(min(120, n))

        val (mean30, std30) = computeMeanAndStd(s30)
        val (mean60, std60) = computeMeanAndStd(s60)
        val (mean120, std120) = computeMeanAndStd(s120)

        val hourAngle = 2.0 * PI * (hourOfDay / 24.0)

        return EtaFeatures(
            meanSpeed30s = mean30,
            stdSpeed30s = std30,
            meanSpeed60s = mean60,
            stdSpeed60s = std60,
            meanSpeed120s = mean120,
            stdSpeed120s = std120,
            remainingDistanceM = remainingDistanceM,
            serverEtaSec = serverEtaSec,
            serverEtaAgeSec = serverEtaAgeSec,
            stopsCountOver20s = stopCountOver20s,
            hourSin = sin(hourAngle),
            hourCos = cos(hourAngle),
            dayOfWeek = dayOfWeek
        )
    }

    /**
     * Analytical gradient-boosted decision tree approximation (eta-lite-1.3.0).
     */
    private fun runInference(f: EtaFeatures): EtaModel.Prediction {
        var correctionSec = 0.0
        var confidence = 0.50

        // Factor 1: Stale server ETA adjustment
        // If server ETA was issued 45s ago, travel time elapsed must be accounted for
        if (f.serverEtaAgeSec > 0) {
            correctionSec -= min(f.serverEtaAgeSec.toDouble(), f.serverEtaSec * 0.5)
            confidence += 0.15
        }

        // Factor 2: Traffic congestion vs server expected speed
        val impliedExpectedSpeed = if (f.serverEtaSec > 0) f.remainingDistanceM.toDouble() / f.serverEtaSec else 10.0
        if (f.meanSpeed60s > 0.5 && impliedExpectedSpeed > 0.5) {
            val speedRatio = f.meanSpeed60s / impliedExpectedSpeed
            if (speedRatio < 0.6) {
                // Moving much slower than expected (traffic jam)
                val delay = (impliedExpectedSpeed - f.meanSpeed60s) * (f.serverEtaSec / impliedExpectedSpeed) * 0.35
                correctionSec += min(delay, 600.0) // cap max delay at 10 min
                confidence += 0.20
            } else if (speedRatio > 1.4) {
                // Moving faster (empty highway)
                val reduction = (f.meanSpeed60s - impliedExpectedSpeed) * (f.serverEtaSec / impliedExpectedSpeed) * 0.25
                correctionSec -= min(reduction, 300.0)
                confidence += 0.15
            }
        }

        // Factor 3: Frequent stop penalty (traffic lights, roadblocks)
        if (f.stopsCountOver20s >= 2) {
            correctionSec += (f.stopsCountOver20s * 25.0)
            confidence += 0.10
        }

        // High variance in speed (stop-and-go driving)
        if (f.stdSpeed60s > 4.0) {
            correctionSec += 15.0
        }

        val clampedConfidence = min(0.95, max(0.20, confidence))

        return EtaModel.Prediction(
            etaCorrectionSec = correctionSec.toInt(),
            confidence = clampedConfidence,
            modelVersion = "eta-lite-1.3.0"
        )
    }

    private fun computeMeanAndStd(values: List<Double>): Pair<Double, Double> {
        if (values.isEmpty()) return 0.0 to 0.0
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return mean to sqrt(variance)
    }
}
