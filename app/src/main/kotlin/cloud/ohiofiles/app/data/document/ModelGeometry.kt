package cloud.ohiofiles.app.data.document

import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

data class NativeModelAsset(val bytes: ByteArray, val json: Boolean, val resources: Map<String, ByteArray> = emptyMap())
object ModelGeometry {
    const val MAX_TRIANGLES = 250_000
    fun obj(source: String): FloatArray {
        val vertices = mutableListOf<FloatArray>(); val triangles = ArrayList<Float>()
        source.lineSequence().forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.firstOrNull() == "v") { require(parts.size >= 4); vertices += floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat()); require(vertices.size <= 1_000_000) { "Model has too many vertices" } }
            if (parts.firstOrNull() == "f") {
                val indices = parts.drop(1).takeWhile { !it.startsWith('#') }.map { part -> val value = part.substringBefore('/').toInt(); (if (value > 0) value - 1 else vertices.size + value).also { require(it in vertices.indices) { "OBJ references a missing vertex" } } }
                require(indices.size in 3..1000) { "OBJ polygon is invalid" }
                for (index in 1 until indices.lastIndex) { require(triangles.size / 9 < MAX_TRIANGLES) { "Model exceeds 250,000 triangles" }; listOf(indices[0], indices[index], indices[index + 1]).forEach { vertex -> vertices[vertex].forEach(triangles::add) } }
            }
        }; require(triangles.isNotEmpty()) { "OBJ has no triangle faces" }; return triangles.toFloatArray().also(::validate)
    }
    fun stl(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = if (bytes.size >= 84) buffer.getInt(80).toLong() and 0xffffffffL else -1
        val binary = count >= 0 && 84L + count * 50L == bytes.size.toLong()
        val vertices = if (binary) {
            require(count in 1..MAX_TRIANGLES.toLong()) { "STL exceeds 250,000 triangles" }
            FloatArray(count.toInt() * 9).also { output -> repeat(count.toInt()) { face -> val start = 84 + face * 50 + 12; repeat(9) { output[face * 9 + it] = buffer.getFloat(start + it * 4) } } }
        } else {
            val result = mutableListOf<Float>(); bytes.toString(Charsets.UTF_8).lineSequence().forEach { line -> val parts = line.trim().split(Regex("\\s+")); if (parts.firstOrNull() == "vertex") { require(parts.size >= 4); require(result.size < MAX_TRIANGLES * 9) { "STL exceeds 250,000 triangles" }; result += listOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat()) } }
            require(result.isNotEmpty() && result.size % 9 == 0) { "STL has invalid triangle data" }; result.toFloatArray()
        }; validate(vertices); return vertices
    }
    private fun validate(vertices: FloatArray) { require(vertices.all { it.isFinite() && kotlin.math.abs(it) <= 1e12f }) { "Model contains invalid coordinates" } }
    fun glb(vertices: FloatArray): NativeModelAsset {
        validate(vertices); val normals = FloatArray(vertices.size)
        for (offset in vertices.indices step 9) {
            val ax = vertices[offset + 3] - vertices[offset]; val ay = vertices[offset + 4] - vertices[offset + 1]; val az = vertices[offset + 5] - vertices[offset + 2]
            val bx = vertices[offset + 6] - vertices[offset]; val by = vertices[offset + 7] - vertices[offset + 1]; val bz = vertices[offset + 8] - vertices[offset + 2]
            val normal = floatArrayOf(ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx); val length = sqrt(normal.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(.000001f)
            repeat(3) { corner -> repeat(3) { component -> normals[offset + corner * 3 + component] = normal[component] / length } }
        }
        val bin = ByteBuffer.allocate(vertices.size * 8).order(ByteOrder.LITTLE_ENDIAN); vertices.forEach(bin::putFloat); normals.forEach(bin::putFloat)
        val minimum = (0..2).map { axis -> vertices.indices.step(3).minOf { vertices[it + axis] } }; val maximum = (0..2).map { axis -> vertices.indices.step(3).maxOf { vertices[it + axis] } }
        val json = buildJsonObject {
            putJsonObject("asset") { put("version", "2.0"); put("generator", "OhioFiles Android") }
            put("scene", 0); putJsonArray("scenes") { add(buildJsonObject { put("nodes", JsonArray(listOf(JsonPrimitive(0)))) }) }; putJsonArray("nodes") { add(buildJsonObject { put("mesh", 0) }) }
            putJsonArray("buffers") { add(buildJsonObject { put("byteLength", bin.capacity()) }) }
            putJsonArray("bufferViews") { repeat(2) { index -> add(buildJsonObject { put("buffer", 0); put("byteOffset", index * vertices.size * 4); put("byteLength", vertices.size * 4); put("target", 34962) }) } }
            putJsonArray("accessors") { repeat(2) { index -> add(buildJsonObject { put("bufferView", index); put("componentType", 5126); put("count", vertices.size / 3); put("type", "VEC3"); if (index == 0) { put("min", JsonArray(minimum.map(::JsonPrimitive))); put("max", JsonArray(maximum.map(::JsonPrimitive))) } }) } }
            putJsonArray("materials") { add(buildJsonObject { put("doubleSided", true); putJsonObject("pbrMetallicRoughness") { put("baseColorFactor", JsonArray(listOf(.52f, .71f, .96f, 1f).map(::JsonPrimitive))); put("metallicFactor", 0); put("roughnessFactor", .7) } }) }
            putJsonArray("meshes") { add(buildJsonObject { putJsonArray("primitives") { add(buildJsonObject { putJsonObject("attributes") { put("POSITION", 0); put("NORMAL", 1) }; put("material", 0); put("mode", 4) }) } }) }
        }; return NativeModelAsset(pack(json, bin.array()), false)
    }
    private fun pack(json: JsonObject, binary: ByteArray): ByteArray {
        val text = json.toString().toByteArray(); val padded = (text.size + 3) / 4 * 4; val binaryPadded = (binary.size + 3) / 4 * 4
        return ByteBuffer.allocate(12 + 8 + padded + 8 + binaryPadded).order(ByteOrder.LITTLE_ENDIAN).apply { putInt(0x46546c67); putInt(2); putInt(capacity()); putInt(padded); putInt(0x4e4f534a); put(text); repeat(padded - text.size) { put(0x20.toByte()) }; putInt(binaryPadded); putInt(0x004e4942); put(binary); repeat(binaryPadded - binary.size) { put(0.toByte()) } }.array()
    }
    fun unpack(asset: NativeModelAsset): Pair<JsonObject, Map<String, ByteArray>> {
        if (asset.json) return Json.parseToJsonElement(asset.bytes.toString(Charsets.UTF_8)).jsonObject to asset.resources
        val buffer = ByteBuffer.wrap(asset.bytes).order(ByteOrder.LITTLE_ENDIAN); require(buffer.int == 0x46546c67 && buffer.int == 2) { "Invalid GLB header" }; require(buffer.int == asset.bytes.size) { "Incomplete GLB" }
        var json: JsonObject? = null; var binary = ByteArray(0)
        while (buffer.remaining() >= 8) { val length = buffer.int; val type = buffer.int; require(length >= 0 && length <= buffer.remaining()); val data = ByteArray(length); buffer.get(data); when (type) { 0x4e4f534a -> json = Json.parseToJsonElement(data.toString(Charsets.UTF_8).trimEnd(' ', '\u0000')).jsonObject; 0x004e4942 -> binary = data } }
        val document = json ?: error("GLB has no JSON scene")
        val buffers = document["buffers"]?.jsonArray.orEmpty().mapIndexed { index, item -> if (index == 0 && item.jsonObject["uri"] == null) JsonObject(item.jsonObject + ("uri" to JsonPrimitive("ohio-internal-buffer.bin"))) else item }
        return JsonObject(document + ("buffers" to JsonArray(buffers))) to (asset.resources + ("ohio-internal-buffer.bin" to binary))
    }
    fun wireframe(asset: NativeModelAsset, lightBackground: Boolean = false): NativeModelAsset {
        val (source, resources) = unpack(asset); val views = source["bufferViews"]?.jsonArray.orEmpty().toMutableList(); val accessors = source["accessors"]?.jsonArray.orEmpty().toMutableList(); val buffers = source["buffers"]?.jsonArray.orEmpty().toMutableList()
        val output = java.io.ByteArrayOutputStream(); val bufferIndex = buffers.size
        val meshes = source["meshes"]?.jsonArray.orEmpty().map { mesh -> JsonObject(mesh.jsonObject + ("primitives" to JsonArray(mesh.jsonObject["primitives"]!!.jsonArray.map { primitive ->
            val item = primitive.jsonObject; val mode = item["mode"]?.jsonPrimitive?.int ?: 4
            require(mode == 4) { "Wireframe is available for triangle mesh primitives" }; require(item["extensions"]?.jsonObject?.containsKey("KHR_draco_mesh_compression") != true) { "Wireframe is unavailable for Draco-compressed geometry" }
            val indices = if (item["indices"] != null) {
                val accessor = accessors[item["indices"]!!.jsonPrimitive.int].jsonObject; val view = views[accessor["bufferView"]?.jsonPrimitive?.int ?: error("Sparse wireframe indices are unsupported")].jsonObject
                val buffer = buffers[view["buffer"]!!.jsonPrimitive.int].jsonObject; val data = resources[buffer["uri"]!!.jsonPrimitive.content] ?: error("A model buffer is unavailable")
                val bytes = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN); val offset = (accessor["byteOffset"]?.jsonPrimitive?.int ?: 0) + (view["byteOffset"]?.jsonPrimitive?.int ?: 0); val component = accessor["componentType"]!!.jsonPrimitive.int
                val size = when (component) { 5121 -> 1; 5123 -> 2; 5125 -> 4; else -> error("Invalid mesh index type") }; val stride = view["byteStride"]?.jsonPrimitive?.int ?: size; val count = accessor["count"]!!.jsonPrimitive.int
                require(count <= MAX_TRIANGLES * 3 && offset >= 0 && offset.toLong() + (count - 1).coerceAtLeast(0).toLong() * stride + size <= data.size) { "Model index data exceeds the preview limit" }
                IntArray(count) { index -> when (size) { 1 -> bytes.get(offset + index * stride).toInt() and 255; 2 -> bytes.getShort(offset + index * stride).toInt() and 65535; else -> bytes.getInt(offset + index * stride).also { require(it >= 0) } } }
            } else {
                val position = item["attributes"]!!.jsonObject["POSITION"]!!.jsonPrimitive.int; val count = accessors[position].jsonObject["count"]!!.jsonPrimitive.int; require(count <= MAX_TRIANGLES * 3); IntArray(count) { it }
            }
            require(indices.size % 3 == 0)
            val offset = output.size(); val lines = ByteBuffer.allocate(indices.size * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (index in indices.indices step 3) { val a = indices[index]; val b = indices[index + 1]; val c = indices[index + 2]; intArrayOf(a, b, b, c, c, a).forEach(lines::putInt) }; output.write(lines.array())
            val viewIndex = views.size; views += buildJsonObject { put("buffer", bufferIndex); put("byteOffset", offset); put("byteLength", lines.capacity()); put("target", 34963) }
            val accessorIndex = accessors.size; accessors += buildJsonObject { put("bufferView", viewIndex); put("componentType", 5125); put("count", indices.size * 2); put("type", "SCALAR") }
            JsonObject(item + mapOf("mode" to JsonPrimitive(1), "indices" to JsonPrimitive(accessorIndex), "material" to JsonPrimitive(0)))
        }))) }
        buffers += buildJsonObject { put("uri", "ohio-generated-wireframe.bin"); put("byteLength", output.size()) }
        val material = buildJsonObject { put("doubleSided", true); putJsonObject("extensions") { putJsonObject("KHR_materials_unlit") {} }; putJsonObject("pbrMetallicRoughness") { put("baseColorFactor", JsonArray((if (lightBackground) listOf(.025f, .09f, .18f, 1f) else listOf(.3f, .85f, 1f, 1f)).map(::JsonPrimitive))) } }
        val json = JsonObject(source + mapOf("meshes" to JsonArray(meshes), "buffers" to JsonArray(buffers), "bufferViews" to JsonArray(views), "accessors" to JsonArray(accessors), "materials" to JsonArray(listOf(material)), "extensionsUsed" to JsonArray(((source["extensionsUsed"] as? JsonArray)?.toList().orEmpty() + JsonPrimitive("KHR_materials_unlit")).distinct())))
        return NativeModelAsset(json.toString().toByteArray(), true, resources + ("ohio-generated-wireframe.bin" to output.toByteArray()))
    }
    fun statistics(asset: NativeModelAsset): Pair<Int, Int> {
        val (json, _) = unpack(asset); val accessors = json["accessors"]?.jsonArray.orEmpty(); var vertices = 0; var triangles = 0
        json["meshes"]?.jsonArray.orEmpty().forEach { mesh -> mesh.jsonObject["primitives"]?.jsonArray.orEmpty().forEach { value -> val primitive = value.jsonObject
            val position = primitive["attributes"]?.jsonObject?.get("POSITION")?.jsonPrimitive?.int
            val count = position?.let { accessors[it].jsonObject["count"]?.jsonPrimitive?.int } ?: 0; vertices += count
            val indices = primitive["indices"]?.jsonPrimitive?.int?.let { accessors[it].jsonObject["count"]?.jsonPrimitive?.int } ?: count
            if ((primitive["mode"]?.jsonPrimitive?.int ?: 4) == 4) triangles += indices / 3
        } }; return vertices to triangles
    }
    fun grid(asset: NativeModelAsset): NativeModelAsset {
        val (json, resources) = unpack(asset); val accessors = json["accessors"]?.jsonArray.orEmpty().toMutableList(); val views = json["bufferViews"]?.jsonArray.orEmpty().toMutableList(); val buffers = json["buffers"]?.jsonArray.orEmpty().toMutableList(); val meshes = json["meshes"]?.jsonArray.orEmpty().toMutableList(); val nodes = json["nodes"]?.jsonArray.orEmpty().toMutableList(); val materials = json["materials"]?.jsonArray.orEmpty().toMutableList(); val scenes = json["scenes"]?.jsonArray.orEmpty().toMutableList()
        val positionIndices = meshes.flatMap { it.jsonObject["primitives"]?.jsonArray.orEmpty() }.mapNotNull { it.jsonObject["attributes"]?.jsonObject?.get("POSITION")?.jsonPrimitive?.int }.distinct()
        val min = FloatArray(3) { axis -> positionIndices.mapNotNull { index -> accessors[index].jsonObject["min"]?.jsonArray?.getOrNull(axis)?.jsonPrimitive?.floatOrNull }.minOrNull() ?: -1f }
        val max = FloatArray(3) { axis -> positionIndices.mapNotNull { index -> accessors[index].jsonObject["max"]?.jsonArray?.getOrNull(axis)?.jsonPrimitive?.floatOrNull }.maxOrNull() ?: 1f }
        val extent = (0..2).maxOf { max[it] - min[it] }.coerceAtLeast(.001f) * .7f; val cx = (min[0] + max[0]) / 2; val cz = (min[2] + max[2]) / 2; val y = min[1] - extent * .01f
        val data = ByteBuffer.allocate(21 * 4 * 3 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (index in -10..10) { val offset = index * extent / 10; floatArrayOf(cx + offset, y, cz - extent, cx + offset, y, cz + extent, cx - extent, y, cz + offset, cx + extent, y, cz + offset).forEach(data::putFloat) }
        val bufferIndex = buffers.size; buffers += buildJsonObject { put("uri", "ohio-ground-grid.bin"); put("byteLength", data.capacity()) }
        val viewIndex = views.size; views += buildJsonObject { put("buffer", bufferIndex); put("byteLength", data.capacity()); put("target", 34962) }
        val accessorIndex = accessors.size; accessors += buildJsonObject { put("bufferView", viewIndex); put("componentType", 5126); put("count", 84); put("type", "VEC3"); put("min", JsonArray(listOf(cx - extent, y, cz - extent).map(::JsonPrimitive))); put("max", JsonArray(listOf(cx + extent, y, cz + extent).map(::JsonPrimitive))) }
        val materialIndex = materials.size; materials += buildJsonObject { putJsonObject("extensions") { putJsonObject("KHR_materials_unlit") {} }; putJsonObject("pbrMetallicRoughness") { put("baseColorFactor", JsonArray(listOf(.32f, .36f, .43f, 1f).map(::JsonPrimitive))) } }
        val meshIndex = meshes.size; meshes += buildJsonObject { putJsonArray("primitives") { add(buildJsonObject { putJsonObject("attributes") { put("POSITION", accessorIndex) }; put("mode", 1); put("material", materialIndex) }) } }
        val nodeIndex = nodes.size; nodes += buildJsonObject { put("mesh", meshIndex); put("name", "OhioFiles ground grid") }
        val sceneIndex = json["scene"]?.jsonPrimitive?.int ?: 0; require(sceneIndex in scenes.indices) { "Model has no scene" }; val scene = scenes[sceneIndex].jsonObject; scenes[sceneIndex] = JsonObject(scene + ("nodes" to JsonArray(scene["nodes"]?.jsonArray.orEmpty() + JsonPrimitive(nodeIndex))))
        val output = JsonObject(json + mapOf("buffers" to JsonArray(buffers), "bufferViews" to JsonArray(views), "accessors" to JsonArray(accessors), "materials" to JsonArray(materials), "meshes" to JsonArray(meshes), "nodes" to JsonArray(nodes), "scenes" to JsonArray(scenes), "extensionsUsed" to JsonArray((json["extensionsUsed"]?.jsonArray.orEmpty() + JsonPrimitive("KHR_materials_unlit")).distinct())))
        return NativeModelAsset(output.toString().toByteArray(), true, resources + ("ohio-ground-grid.bin" to data.array()))
    }
}
