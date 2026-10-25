package com.clrg.core.pose

/**
 * A held object (a stick, a toy racket, a rolled-up magazine) seen in one person's hand, in the same units as that
 * person's landmarks: the grip point ([baseX], [baseY]) and the far end ([tipX], [tipY]).
 */
class HeldObservation(
    /** Which hand holds it: 0 = the person's left hand (BlazePose LEFT_* side), 1 = right. */
    val hand: Int,
    val baseX: Float,
    val baseY: Float,
    val tipX: Float,
    val tipY: Float,
    /** 0..1 how sure the tracker is that it really saw the object this frame. */
    val confidence: Float,
) {
    fun mapped(fx: (Float) -> Float, fy: (Float) -> Float) = HeldObservation(hand, fx(baseX), fy(baseY), fx(tipX), fy(tipY), confidence)
}
