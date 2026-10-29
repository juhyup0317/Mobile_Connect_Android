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
enum class EntryKind {
    @SerialName("dir") DIR,
    @SerialName("file") FILE,
    @SerialName("other") OTHER
}

@Serializable
data class Entry(
    val name: String,
    val type: EntryKind = EntryKind.FILE,
    val size: Double = 0.0,
    val mtime: Double = 0.0,
    val link: Boolean = false
) {
    val id: String get() = name
    val isDir: Boolean get() = type == EntryKind.DIR
}

@Serializable
data class Listing(
    val path: String,
    val entries: List<Entry> = emptyList(),
    val truncated: Boolean = false
)

@Serializable
data class SearchHit(
    val path: String,
    val dir: Boolean = false
) {
    val id: String get() = path
}

@Serializable
enum class FileKind {
    @SerialName("text") TEXT,
    @SerialName("image") IMAGE,
    @SerialName("pdf") PDF,
    @SerialName("binary") BINARY,
    @SerialName("large") LARGE
}

@Serializable
data class FileInfo(
    val path: String,
    val size: Double = 0.0,
    val mtime: Double = 0.0,
    val kind: FileKind = FileKind.TEXT,
    val text: String? = null
)

@Serializable
enum class SortKey(val title: String) {
    @SerialName("name") NAME("Name"),
    @SerialName("mtime") MTIME("Date Modified"),
    @SerialName("size") SIZE("Size")
}

@Serializable
data class UiState(
    var cwd: String? = null,
    var homeDir: String? = null,
    var showHidden: Boolean = false,
    var sort: SortKey = SortKey.NAME,
    var sortDesc: Boolean = false,
    var foldersFirst: Boolean = true,
    var bookmarks: List<String> = emptyList(),
    var recent: List<String> = emptyList()
)

object PresetSerializer : KSerializer<Preset> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Preset", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Preset) = encoder.encodeString(value.rawValue)
    override fun deserialize(decoder: Decoder): Preset {
        val raw = decoder.decodeString()
        return Preset.entries.firstOrNull { it.rawValue == raw } ?: Preset.SHELL
    }
}

@Serializable(with = PresetSerializer::class)
enum class Preset(val rawValue: String, val title: String, val iconName: String) {
    SHELL("shell", "Shell", "terminal"),
    CLAUDE("claude", "Claude Code", "sparkles"),
    CODEX("codex", "Codex", "sparkles"),
    COPILOT("copilot", "Copilot", "code");
}

object ActivitySerializer : KSerializer<Activity> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Activity", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Activity) = encoder.encodeString(value.rawValue)
    override fun deserialize(decoder: Decoder): Activity {
        val raw = decoder.decodeString()
        return Activity.entries.firstOrNull { it.rawValue == raw } ?: Activity.IDLE
    }
}

@Serializable(with = ActivitySerializer::class)
enum class Activity(val rawValue: String) {
    IDLE("idle"),
    RUNNING("running"),
    DONE("done"),
    ASK("ask")
}

object NoteKindSerializer : KSerializer<TermNote.Kind> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("TermNoteKind", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: TermNote.Kind) = encoder.encodeString(value.rawValue)
    override fun deserialize(decoder: Decoder): TermNote.Kind {
        val raw = decoder.decodeString()
        return TermNote.Kind.entries.firstOrNull { it.rawValue == raw } ?: TermNote.Kind.DONE
    }
}

@Serializable
data class TermNote(
    val id: Int,
    val at: Double,
    val term: String,
    val preset: Preset = Preset.SHELL,
    val cwd: String,
    val kind: Kind = Kind.DONE,
    val body: String,
    var read: Boolean = false
) {
    @Serializable(with = NoteKindSerializer::class)
    enum class Kind(val rawValue: String) {
        DONE("done"),
        FAILED("failed"),
        ASK("ask")
    }
}

@Serializable
data class TermSession(
    val id: String,
    val title: String = "",
    val preset: Preset = Preset.SHELL,
    val cwd: String = "",
    val lastActive: Double = 0.0,
    val exited: Boolean = false,
    val exitCode: Int? = null,
    var activity: Activity? = null,
    var activityAt: Double? = null
) {
    fun displayName(path: String): String {
        val folder = if (path == "/" || path == "~") path else Path.name(path)
        return if (preset == Preset.SHELL) folder else "${preset.title} · $folder"
    }
}

object Path {
    fun parent(p: String): String {
        val parts = p.split("/").filter { it.isNotEmpty() }.dropLast(1)
        return "/" + parts.joinToString("/")
    }

    fun name(p: String): String {
        return p.split("/").lastOrNull { it.isNotEmpty() } ?: "/"
    }

    fun join(dir: String, name: String): String {
        return if (dir.endsWith("/")) dir + name else "$dir/$name"
    }
}
