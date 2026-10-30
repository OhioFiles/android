package cloud.ohiofiles.app.ui.screens.nativefeatures

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.serialization.json.*

@Composable internal fun rememberChatDraftStore(): SnapshotStateMap<String, String> = rememberSaveable(saver = listSaver<SnapshotStateMap<String, String>, String>(save = { it.entries.flatMap { entry -> listOf(entry.key, entry.value) } }, restore = { saved -> mutableStateMapOf<String, String>().apply { saved.chunked(2).filter { it.size == 2 }.forEach { put(it[0], it[1]) } } })) { mutableStateMapOf() }
internal class ChatDraftState<T>(private val store: MutableMap<String, String>, private val key: String, private val empty: T, private val decode: (String) -> T, private val encode: (T) -> String?) : MutableState<T> {
    override var value: T
        get() = store[key]?.let { runCatching { decode(it) }.getOrDefault(empty) } ?: empty
        set(value) { val encoded = encode(value); if (encoded == null) store.remove(key) else store[key] = encoded }
    override fun component1() = value
    override fun component2(): (T) -> Unit = { value = it }
}
@Composable internal fun rememberChatAttachmentSet(store: SnapshotStateMap<String, String>, key: String): MutableState<Set<String>> = remember(store, key) { ChatDraftState(store, key, emptySet(), { source -> Json.parseToJsonElement(source).jsonArray.map { it.jsonPrimitive.content }.toSet() }, { set -> if (set.isEmpty()) null else JsonArray(set.map(::JsonPrimitive)).toString() }) }
@Composable internal fun rememberChatNullableText(store: SnapshotStateMap<String, String>, key: String): MutableState<String?> = remember(store, key) { ChatDraftState(store, key, null, { it }, { it }) }
@Composable internal fun rememberChatText(store: SnapshotStateMap<String, String>, key: String): MutableState<String> = remember(store, key) { ChatDraftState(store, key, "", { it }, { it.takeIf(String::isNotEmpty) }) }
