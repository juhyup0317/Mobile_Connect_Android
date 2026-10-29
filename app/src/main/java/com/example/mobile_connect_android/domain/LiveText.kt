package com.example.mobile_connect_android.domain

/**
 * Keeps the text on screen equal to what has been said so far, erasing and retyping only the part that changed (the
 * recognizer sends everything said so far each time, and keeps revising its last few words).
 */
class LiveText {
    private var typed: IntArray = IntArray(0)

    fun apply(text: String, erase: (Int) -> Unit, type: (String) -> Unit) {
        val new = text.codePoints().toArray()
        var common = 0
        while (common < typed.size && common < new.size && typed[common] == new[common]) common++
        if (typed.size > common) erase(typed.size - common)
        if (new.size > common) type(String(new, common, new.size - common))
        typed = new
    }
}
