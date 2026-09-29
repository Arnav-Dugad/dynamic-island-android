package com.arnav.island.animation

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A single damped spring (mass = 1) advanced with the closed-form solution of the damped harmonic
 * oscillator. Because the solution is analytic, a step is exact for any frame duration: the motion
 * is identical at 60, 90 or 120 Hz, and a dropped frame never destabilises it.
 *
 * Retargeting ([animateTo]) never resets [velocity], which is what makes interruption and reversal
 * feel physically continuous: a collapsing island that is told to expand again simply decelerates
 * and turns around instead of snapping.
 */
class Spring(
    initialValue: Float = 0f,
    spec: SpringSpec = SpringSpec.Natural,
    /** Distance from the target (in the spring's own units) that counts as "arrived". */
    var restThreshold: Float = 0.25f,
) {
    var value: Float = initialValue
        private set
    var target: Float = initialValue
        private set
    var velocity: Float = 0f
        private set

    var stiffness: Float = spec.stiffness
        private set
    var dampingRatio: Float = spec.dampingRatio
        private set

    val isAtRest: Boolean
        get() = abs(value - target) <= restThreshold && abs(velocity) <= restThreshold * VELOCITY_REST_FACTOR

    fun setSpec(spec: SpringSpec) {
        stiffness = spec.stiffness
        dampingRatio = spec.dampingRatio
    }

    /** Moves the rest position, keeping current value and velocity (interruptible). */
    fun animateTo(newTarget: Float, spec: SpringSpec? = null) {
        if (spec != null) setSpec(spec)
        target = newTarget
    }

    /** Jumps to [newValue] with no motion. */
    fun snapTo(newValue: Float) {
        value = newValue
        target = newValue
        velocity = 0f
    }

    /** Adds velocity, e.g. from a fling or a tap "kick". Units per second. */
    fun impulse(deltaVelocity: Float) {
        velocity += deltaVelocity
    }

    /** Overrides the velocity, e.g. to hand over gesture velocity on release. */
    fun setVelocity(newVelocity: Float) {
        velocity = newVelocity
    }

    /**
     * Advances the spring by [dtSeconds]. Returns true while the spring is still moving.
     */
    fun step(dtSeconds: Float): Boolean {
        if (isAtRest) {
            value = target
            velocity = 0f
            return false
        }
        val dt = dtSeconds.coerceIn(0f, MAX_STEP)
        if (dt == 0f) return true

        val x0 = value - target
        val v0 = velocity
        val omega = sqrt(stiffness)
        val zeta = dampingRatio

        val x: Float
        val v: Float
        when {
            zeta < 1f -> {
                val a = zeta * omega
                val wd = omega * sqrt(1f - zeta * zeta)
                val c1 = x0
                val c2 = (v0 + a * x0) / wd
                val e = exp(-a * dt)
                val cs = cos(wd * dt)
                val sn = sin(wd * dt)
                x = e * (c1 * cs + c2 * sn)
                v = e * ((-a * c1 + wd * c2) * cs + (-a * c2 - wd * c1) * sn)
            }
            zeta == 1f -> {
                val b = v0 + omega * x0
                val e = exp(-omega * dt)
                x = (x0 + b * dt) * e
                v = (v0 - omega * b * dt) * e
            }
            else -> {
                val s = sqrt(zeta * zeta - 1f)
                val r1 = -omega * (zeta - s)
                val r2 = -omega * (zeta + s)
                val c2 = (v0 - r1 * x0) / (r2 - r1)
                val c1 = x0 - c2
                val e1 = exp(r1 * dt)
                val e2 = exp(r2 * dt)
                x = c1 * e1 + c2 * e2
                v = c1 * r1 * e1 + c2 * r2 * e2
            }
        }
        value = target + x
        velocity = v
        if (isAtRest) {
            value = target
            velocity = 0f
            return false
        }
        return true
    }

    companion object {
        /** Longest single step we integrate; longer gaps (e.g. after screen-off) just settle. */
        const val MAX_STEP = 0.1f
        private const val VELOCITY_REST_FACTOR = 20f
    }
}
