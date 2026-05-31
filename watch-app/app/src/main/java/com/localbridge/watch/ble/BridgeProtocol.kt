package com.localbridge.watch.ble

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.ceil

data class BridgeEnvelope(
    val id: String = UUID.randomUUID().toString(),
    val type: String,
    val timestampSeconds: Long = System.currentTimeMillis() / 1000,
    val payload: JSONObject = JSONObject(),
    val version: Int = BridgeGattProfile.PROTOCOL_VERSION
) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", version)
        .put("id", id)
        .put("type", type)
        .put("ts", timestampSeconds)
        .put("payload", payload)

    fun toBytes(): ByteArray = toJson().toString().toByteArray(StandardCharsets.UTF_8)
}

object BridgeProtocol {
    const val MAX_CHUNK_BYTES = 160

    fun decode(bytes: ByteArray): BridgeEnvelope {
        val json = JSONObject(bytes.toString(StandardCharsets.UTF_8))
        return BridgeEnvelope(
            id = json.getString("id"),
            type = json.getString("type"),
            timestampSeconds = json.optLong("ts", System.currentTimeMillis() / 1000),
            payload = json.optJSONObject("payload") ?: JSONObject(),
            version = json.optInt("v", 1)
        )
    }

    fun ack(forId: String) = BridgeEnvelope(
        type = "ack",
        payload = JSONObject().put("for", forId)
    )

    fun chunk(envelope: BridgeEnvelope): List<ByteArray> {
        val text = envelope.toJson().toString()
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= MAX_CHUNK_BYTES) return listOf(bytes)

        val total = ceil(bytes.size / MAX_CHUNK_BYTES.toDouble()).toInt()
        return bytes.asList().chunked(MAX_CHUNK_BYTES).mapIndexed { index, part ->
            JSONObject()
                .put("chunk", true)
                .put("id", envelope.id)
                .put("seq", index)
                .put("total", total)
                .put("data", Base64.encodeToString(part.toByteArray(), Base64.NO_WRAP))
                .toString()
                .toByteArray(StandardCharsets.UTF_8)
        }
    }
}

class ChunkReassembler {
    private val chunks = mutableMapOf<String, MutableMap<Int, String>>()
    private val totals = mutableMapOf<String, Int>()
    private val duplicateSeqCounts = mutableMapOf<String, Int>()
    private val droppedIds = mutableSetOf<String>()
    private val seen = ArrayDeque<String>()
    private val seenSet = mutableSetOf<String>()

    fun remember(id: String): Boolean {
        if (seenSet.contains(id)) return false
        seen.addLast(id)
        seenSet.add(id)
        while (seen.size > 64) seenSet.remove(seen.removeFirst())
        return true
    }

    fun accept(bytes: ByteArray): ByteArray? {
        val json = JSONObject(bytes.toString(StandardCharsets.UTF_8))
        if (!json.optBoolean("chunk", false)) return bytes

        val id = json.getString("id")
        if (droppedIds.contains(id)) {
            Log.w(TAG, "WRITE_CHUNK_DROPPED id=$id reason=previous_duplicate_drop")
            return null
        }
        val total = json.getInt("total")
        val seq = json.getInt("seq")
        Log.i(TAG, "WRITE_CHUNK id=$id seq=$seq total=$total")
        totals[id] = total
        val map = chunks.getOrPut(id) { mutableMapOf() }
        if (map.containsKey(seq)) {
            val count = (duplicateSeqCounts[id] ?: 0) + 1
            duplicateSeqCounts[id] = count
            Log.w(TAG, "WRITE_CHUNK_DUPLICATE id=$id seq=$seq count=$count")
            if (count > 3) {
                chunks.remove(id)
                totals.remove(id)
                duplicateSeqCounts.remove(id)
                droppedIds.add(id)
                Log.w(TAG, "WRITE_CHUNK_DUPLICATE_DROP id=$id seq=$seq total=$total")
            }
            return null
        }
        map[seq] = json.getString("data")
        if (map.size != total) return null

        val merged = (0 until total).fold(ByteArray(0)) { acc, index ->
            acc + Base64.decode(map.getValue(index), Base64.NO_WRAP)
        }
        chunks.remove(id)
        totals.remove(id)
        duplicateSeqCounts.remove(id)
        droppedIds.remove(id)
        return merged
    }

    companion object {
        private const val TAG = "GalaxyBridgeBle"
    }
}
