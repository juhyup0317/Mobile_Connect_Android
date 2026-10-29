package com.example.mobile_connect_android.data

object InputFilter {
    fun userInput(text: String): String {
        val s = text.toCharArray()
        val out = StringBuilder()
        var i = 0
        while (i < s.size) {
            if (s[i] != '\u001b' || i + 1 >= s.size) {
                out.append(s[i])
                i++
                continue
            }
            when (s[i + 1]) {
                '[' -> { // CSI
                    var j = i + 2
                    while (j < s.size && s[j].code in 0x20..0x3f) {
                        j++
                    }
                    if (j < s.size && s[j].code in 0x40..0x7e) {
                        val body = s.sliceArray((i + 2) until j)
                        val finalChar = s[j]
                        if (allowsCSI(body, finalChar)) {
                            out.append(s, i, j - i + 1)
                        }
                        i = j + 1
                    } else {
                        out.append(s, i, s.size - i)
                        return out.toString()
                    }
                }
                ']', 'P', '_', '^', 'X' -> { // OSC, DCS, APC, PM, SOS
                    i = endOfString(s, i + 2)
                }
                'O' -> { // SS3
                    val end = minOf(i + 3, s.size)
                    out.append(s, i, end - i)
                    i = end
                }
                else -> { // Option+key: ESC followed by key
                    out.append(s[i])
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun allowsCSI(body: CharArray, finalChar: Char): Boolean {
        if (body.isNotEmpty() && body.first() == '<') {
            return finalChar == 'M' || finalChar == 'm'
        }
        if (body.isNotEmpty() && body.first() in listOf('?', '>', '=')) {
            return false
        }
        if (body.any { it.code in 0x20..0x2f }) {
            return false
        }
        return when (finalChar) {
            'A', 'B', 'C', 'D', 'H', 'F', 'Z', '~', 'P', 'Q', 'S', 'u' -> true
            'M' -> body.isEmpty()
            else -> false
        }
    }

    private fun endOfString(s: CharArray, start: Int): Int {
        var j = start
        while (j < s.size) {
            if (s[j].code == 0x07) return j + 1
            if (s[j] == '\u001b' && j + 1 < s.size && s[j + 1] == '\\') return j + 2
            j++
        }
        return s.size
    }
}
