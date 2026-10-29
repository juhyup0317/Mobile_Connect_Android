package com.example.mobile_connect_android.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
data class Prefs(
    var theme: Theme = Theme.DARK,
    var termFontSize: Int = 13,
    var editorFontSize: Int = 13,
    var wrap: Boolean = false,
    var keys: List<KeyId> = KeyId.defaults,
    var keybar: KeybarMode = KeybarMode.AUTO,
    var editorKeys: Boolean = true,
    var uiScale: Double = 1.0,
    var filesWidth: Int = 340,
    var filesCollapsed: Boolean = false
) {
    @Serializable
    enum class Theme(val title: String) {
        @SerialName("system") SYSTEM("System"),
        @SerialName("dark") DARK("Dark"),
        @SerialName("light") LIGHT("Light")
    }

    @Serializable
    enum class KeybarMode(val title: String) {
        @SerialName("auto") AUTO("Auto"),
        @SerialName("always") ALWAYS("Always"),
        @SerialName("never") NEVER("Never")
    }
}

@Serializable
data class PrefsFile(val touch: Prefs? = null)

object KeyIdSerializer : KSerializer<KeyId> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("KeyId", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: KeyId) = encoder.encodeString(value.rawValue)
    override fun deserialize(decoder: Decoder): KeyId {
        val raw = decoder.decodeString()
        return KeyId.entries.firstOrNull { it.rawValue == raw } ?: KeyId.ESC
    }
}

@Serializable(with = KeyIdSerializer::class)
enum class KeyId(val rawValue: String) {
    ESC("esc"), TAB("tab"), CTRL("ctrl"), ALT("alt"), LEFT("left"), UP("up"), DOWN("down"), RIGHT("right"),
    STAB("stab"), SHIFT("shift"), CTRLC("ctrlc"), CTRLD("ctrld"), CTRLZ("ctrlz"), CTRLR("ctrlr"), CTRLL("ctrll"),
    CTRLA("ctrla"), CTRLB("ctrlb"), CTRLE("ctrle"), CTRLK("ctrlk"), CTRLU("ctrlu"), CTRLW("ctrlw"),
    HOME("home"), END("end"), PGUP("pgup"), PGDN("pgdn"), DEL("del"), INS("ins"),
    PIPE("pipe"), TILDE("tilde"), SLASH("slash"), BACKTICK("backtick"), DASH("dash"), UNDERSCORE("underscore"), BACKSLASH("backslash"),
    F1("f1"), F2("f2"), F3("f3"), F4("f4"), F5("f5"), F6("f6"), F7("f7"), F8("f8"), F9("f9"), F10("f10"), F11("f11"), F12("f12"),
    PASTE("paste"), KBD("kbd");

    val id: String get() = rawValue

    enum class Modifier { CTRL, ALT, SHIFT }

    val modifier: Modifier?
        get() = when (this) {
            CTRL -> Modifier.CTRL
            ALT -> Modifier.ALT
            SHIFT -> Modifier.SHIFT
            else -> null
        }

    val label: String
        get() = when (this) {
            ESC -> "esc"
            TAB -> "⇥"
            CTRL -> "⌃"
            ALT -> "⌥"
            LEFT -> "←"
            UP -> "↑"
            DOWN -> "↓"
            RIGHT -> "→"
            STAB -> "⇧⇥"
            SHIFT -> "⇧"
            CTRLC -> "^C"
            CTRLD -> "^D"
            CTRLZ -> "^Z"
            CTRLR -> "^R"
            CTRLL -> "^L"
            HOME -> "home"
            END -> "end"
            PGUP -> "pg↑"
            PGDN -> "pg↓"
            PIPE -> "|"
            TILDE -> "~"
            SLASH -> "/"
            BACKTICK -> "`"
            DASH -> "-"
            UNDERSCORE -> "_"
            PASTE -> "paste"
            KBD -> "⌨"
            CTRLA -> "^A"
            CTRLB -> "^B"
            CTRLE -> "^E"
            CTRLK -> "^K"
            CTRLU -> "^U"
            CTRLW -> "^W"
            DEL -> "del"
            INS -> "ins"
            BACKSLASH -> "\\"
            F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12 -> rawValue.uppercase()
        }

    val title: String
        get() = when (this) {
            ESC -> "Escape"
            TAB -> "Tab"
            CTRL -> "Control (tap once for next key, twice to lock)"
            ALT -> "Option (tap once for next key, twice to lock)"
            LEFT -> "Left arrow"
            UP -> "Up arrow"
            DOWN -> "Down arrow"
            RIGHT -> "Right arrow"
            STAB -> "Shift-Tab (Claude Code: switch mode)"
            SHIFT -> "Shift (tap once for next key, twice to lock)"
            CTRLC -> "Control-C: interrupt"
            CTRLD -> "Control-D: end of input"
            CTRLZ -> "Control-Z: suspend"
            CTRLR -> "Control-R: search history"
            CTRLL -> "Control-L: clear screen"
            HOME -> "Home"
            END -> "End"
            PGUP -> "Page up"
            PGDN -> "Page down"
            PIPE -> "Pipe"
            TILDE -> "Tilde"
            SLASH -> "Slash"
            BACKTICK -> "Backtick"
            DASH -> "Dash"
            UNDERSCORE -> "Underscore"
            PASTE -> "Paste from clipboard"
            KBD -> "Show or hide keyboard"
            CTRLA -> "Control-A: start of line"
            CTRLB -> "Control-B: tmux prefix"
            CTRLE -> "Control-E: end of line"
            CTRLK -> "Control-K: delete to end of line"
            CTRLU -> "Control-U: delete whole line"
            CTRLW -> "Control-W: delete last word"
            DEL -> "Forward delete"
            INS -> "Insert"
            BACKSLASH -> "Backslash"
            F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12 -> "Function key ${rawValue.drop(1)}"
        }

    val group: String
        get() = when (this) {
            ESC, TAB, CTRL, ALT, LEFT, UP, DOWN, RIGHT -> "Keys missing on Mobile"
            STAB, SHIFT, CTRLC, CTRLD, CTRLZ, CTRLR, CTRLL, CTRLA, CTRLB, CTRLE, CTRLK, CTRLU, CTRLW -> "Shortcuts"
            HOME, END, PGUP, PGDN, DEL, INS -> "Navigation"
            F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12 -> "Function keys"
            PIPE, TILDE, SLASH, BACKTICK, DASH, UNDERSCORE, BACKSLASH -> "Symbols"
            PASTE, KBD -> "Tools"
        }

    companion object {
        val defaults: List<KeyId> = listOf(ESC, TAB, CTRL, ALT, STAB, LEFT, UP, DOWN, RIGHT, KBD)

        val groups: List<String>
            get() {
                val seen = mutableListOf<String>()
                for (k in entries) {
                    if (!seen.contains(k.group)) seen.add(k.group)
                }
                return seen
            }
    }
}

data class Mods(
    var ctrl: Boolean = false,
    var alt: Boolean = false,
    var shift: Boolean = false
) {
    val any: Boolean get() = ctrl || alt || shift
    val param: Int get() = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
}

fun keySequence(id: KeyId, mods: Mods, appCursor: Boolean = false): String? {
    val m = mods.param
    val cursor = mapOf(
        KeyId.UP to "A", KeyId.DOWN to "B", KeyId.RIGHT to "C", KeyId.LEFT to "D",
        KeyId.HOME to "H", KeyId.END to "F"
    )
    cursor[id]?.let { c ->
        return if (m > 1) "\u001b[1;${m}$c" else if (appCursor) "\u001bO$c" else "\u001b[$c"
    }

    if (id == KeyId.PGUP || id == KeyId.PGDN) {
        val n = if (id == KeyId.PGUP) 5 else 6
        return if (m > 1) "\u001b[$n;${m}~" else "\u001b[${n}~"
    }

    val tilde = mapOf(
        KeyId.INS to 2, KeyId.DEL to 3, KeyId.F5 to 15, KeyId.F6 to 17,
        KeyId.F7 to 18, KeyId.F8 to 19, KeyId.F9 to 20, KeyId.F10 to 21,
        KeyId.F11 to 23, KeyId.F12 to 24
    )
    tilde[id]?.let { n ->
        return if (m > 1) "\u001b[$n;${m}~" else "\u001b[${n}~"
    }

    val ss3 = mapOf(KeyId.F1 to "P", KeyId.F2 to "Q", KeyId.F3 to "R", KeyId.F4 to "S")
    ss3[id]?.let { c ->
        return if (m > 1) "\u001b[1;${m}$c" else "\u001bO$c"
    }

    return when (id) {
        KeyId.TAB -> if (mods.shift) "\u001b[Z" else if (mods.alt) "\u001b\t" else "\t"
        KeyId.STAB -> "\u001b[Z"
        KeyId.ESC -> if (mods.alt) "\u001b\u001b" else "\u001b"
        KeyId.CTRLC -> applyMods("\u0003", mods)
        KeyId.CTRLD -> applyMods("\u0004", mods)
        KeyId.CTRLZ -> applyMods("\u001a", mods)
        KeyId.CTRLR -> applyMods("\u0012", mods)
        KeyId.CTRLL -> applyMods("\u000c", mods)
        KeyId.CTRLA -> applyMods("\u0001", mods)
        KeyId.CTRLB -> applyMods("\u0002", mods)
        KeyId.CTRLE -> applyMods("\u0005", mods)
        KeyId.CTRLK -> applyMods("\u000b", mods)
        KeyId.CTRLU -> applyMods("\u0015", mods)
        KeyId.CTRLW -> applyMods("\u0017", mods)
        KeyId.BACKSLASH -> applyMods("\\", mods)
        KeyId.PIPE -> applyMods("|", mods)
        KeyId.TILDE -> applyMods("~", mods)
        KeyId.SLASH -> applyMods("/", mods)
        KeyId.BACKTICK -> applyMods("`", mods)
        KeyId.DASH -> applyMods("-", mods)
        KeyId.UNDERSCORE -> applyMods("_", mods)
        else -> null
    }
}

fun applyMods(data: String, mods: Mods): String {
    var out = data
    if (mods.shift && out.length == 1) {
        out = out.uppercase()
    }
    if (mods.ctrl && out.length == 1) {
        val c = out.uppercase().firstOrNull()?.code
        if (c != null) {
            out = when {
                c in 64..95 -> (c - 64).toChar().toString()
                out == " " || out == "2" -> "\u0000"
                out == "?" -> "\u007f"
                c in 51..55 -> (c - 24).toChar().toString()
                else -> out
            }
        }
    }
    if (mods.alt) {
        out = "\u001b$out"
    }
    return out
}
