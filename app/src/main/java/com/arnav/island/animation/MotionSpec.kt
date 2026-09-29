package com.arnav.island.animation

import kotlin.math.PI

/**
 * A spring described the way motion designers think about it: [response] is the approximate
 * period of one oscillation in seconds (lower = faster) and [dampingRatio] controls bounce
 * (1 = no overshoot, < 1 = overshoot).
 */
data class SpringSpec(val response: Float, val dampingRatio: Float) {
    val stiffness: Float
        get() {
            val w = (2.0 * PI / response.coerceAtLeast(0.02f)).toFloat()
            return w * w
        }

    /** Slower/faster and more/less bouncy variants of this spec. */
    fun scaled(speed: Float = 1f, intensity: Float = 1f): SpringSpec {
        val r = response / speed.coerceIn(0.25f, 3f)
        val bounce = (1f - dampingRatio) * intensity.coerceIn(0f, 2f)
        return SpringSpec(r, (1f - bounce).coerceIn(MIN_DAMPING, 1f))
    }

    fun withResponseFactor(factor: Float) = copy(response = response * factor)

    companion object {
        const val MIN_DAMPING = 0.35f

        val Natural = SpringSpec(0.44f, 0.74f)
        val Snappy = SpringSpec(0.32f, 0.84f)
        val Soft = SpringSpec(0.58f, 0.92f)
        val Elastic = SpringSpec(0.50f, 0.58f)

        /** Used when the system "remove animations" / reduce motion setting is on. */
        val Reduced = SpringSpec(0.22f, 1f)
    }
}

enum class MotionPreset(val label: String, val spec: SpringSpec) {
    NATURAL("Natural", SpringSpec.Natural),
    SNAPPY("Snappy", SpringSpec.Snappy),
    SOFT("Soft", SpringSpec.Soft),
    ELASTIC("Elastic", SpringSpec.Elastic),
    CUSTOM("Custom", SpringSpec.Natural),
}

/**
 * Resolved motion parameters for every animated property of the island. Built once whenever the
 * user changes motion settings; the renderer only reads from it.
 */
data class MotionProfile(
    val base: SpringSpec,
    val reduceMotion: Boolean,
) {
    /** Horizontal growth leads expansion; vertical growth follows slightly behind. */
    val widthExpand: SpringSpec get() = pick(base.withResponseFactor(0.92f))
    val heightExpand: SpringSpec get() = pick(base.withResponseFactor(1.08f))

    /** On collapse the order flips: height retracts first, then the pill narrows. */
    val widthCollapse: SpringSpec get() = pick(base.withResponseFactor(1.04f).damped(0.06f))
    val heightCollapse: SpringSpec get() = pick(base.withResponseFactor(0.86f).damped(0.08f))

    val position: SpringSpec get() = pick(base.withResponseFactor(0.95f))
    val bubble: SpringSpec get() = pick(base.withResponseFactor(1.1f))
    val contentIn: SpringSpec get() = pick(SpringSpec(base.response * 0.62f, 1f))
    val contentOut: SpringSpec get() = pick(SpringSpec(base.response * 0.36f, 1f))
    val press: SpringSpec get() = pick(SpringSpec(0.22f, 0.62f))
    val release: SpringSpec get() = pick(SpringSpec(0.38f, 0.55f))
    val gesture: SpringSpec get() = pick(SpringSpec(0.30f, 0.7f))

    private fun pick(spec: SpringSpec) = if (reduceMotion) SpringSpec.Reduced else spec

    private fun SpringSpec.damped(extra: Float) = copy(dampingRatio = (dampingRatio + extra).coerceAtMost(1f))

    companion object {
        fun from(
            preset: MotionPreset,
            speed: Float,
            intensity: Float,
            customResponse: Float,
            customDamping: Float,
            reduceMotion: Boolean,
        ): MotionProfile {
            val raw = if (preset == MotionPreset.CUSTOM) SpringSpec(customResponse, customDamping) else preset.spec
            return MotionProfile(raw.scaled(speed, intensity), reduceMotion)
        }

        val Default = MotionProfile(SpringSpec.Natural, reduceMotion = false)
    }
}
