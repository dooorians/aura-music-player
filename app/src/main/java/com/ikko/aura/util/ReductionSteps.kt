package com.ikko.aura.util

import kotlin.math.roundToInt

/**
 * The sizes a picture of [width] x [height] goes through to end with its SHORTER side at [targetPx]: none when it
 * already fits (a picture is never made larger), otherwise one size per halving at most, the last being the target.
 * The ratio is kept to the pixel.
 *
 * The shorter side, not the longer: a cover is drawn with `ContentScale.Crop` into a square box of [targetPx], which
 * scales the picture until its shorter side fills the box. Reduced to the longer side, a 166 x 141 picture asked for
 * at 130 became 130 x 110 and was then enlarged by a fifth to fill the box, which showed as a soft cover. Reduced to
 * the shorter side the box is filled 1:1 and the crop only cuts what overflows.
 *
 * Halving at most each step keeps a bilinear filter from skipping source pixels, which a single big step would.
 */
internal fun reductionSteps(width: Int, height: Int, targetPx: Int): List<Pair<Int, Int>> {
    val target = targetPx.coerceAtLeast(1)
    val steps = ArrayList<Pair<Int, Int>>(2)
    var w = width
    var h = height
    while (minOf(w, h) > target) {
        val shorter = minOf(w, h)
        val next = maxOf(target, (shorter + 1) / 2)
        val factor = next.toFloat() / shorter
        w = if (w == shorter) next else (w * factor).roundToInt().coerceAtLeast(1)
        h = if (h == shorter) next else (h * factor).roundToInt().coerceAtLeast(1)
        steps += w to h
    }
    return steps
}
