package com.example.pleostest.bridge

import com.example.pleostest.vehicle.ItemCapability
import com.example.pleostest.vehicle.VehicleItem
import com.example.pleostest.vehicle.VehicleQuery
import com.example.pleostest.vehicle.VehicleReadResult
import com.example.pleostest.vehicle.VehicleStatusReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant

const val BRIDGE_PORT = 18765
const val VEHICLE_SDK_VERSION = "2.3.2"
internal const val MAX_HEAD_BYTES = 8 * 1024
internal const val MAX_BODY_BYTES = 1024
private const val MAX_DIAGNOSTIC_CHARS = 512

data class BridgeRequest(val requestId: String, val remainingMs: Long, val query: VehicleQuery)

class BridgeResponse(val status: Int, val json: String) {
    fun toBytes(): ByteArray {
        val body = json.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status ${REASONS.getValue(status)}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        return head.toByteArray(Charsets.US_ASCII) + body
    }

    private companion object {
        val REASONS = mapOf(200 to "OK", 400 to "Bad Request", 403 to "Forbidden",
            409 to "Conflict", 413 to "Content Too Large", 415 to "Unsupported Media Type",
            503 to "Service Unavailable")
    }
}

sealed interface HeadCheck {
    data class Accepted(val contentLength: Int) : HeadCheck
    data class Rejected(val response: BridgeResponse) : HeadCheck
}

/**
 * Wire contract of the single private route. Only one exact HTTP/1.1 shape is accepted; anything
 * ambiguous is rejected rather than resynchronized. Internal messages are never echoed.
 */
object DoorBridgeProtocol {
    private const val REQUEST_LINE = "POST /v2/vehicle-read HTTP/1.1"
    private val TOKEN_NAME = Regex("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+")
    private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val JSON_TYPE = Regex("application/json(\\s*;\\s*charset=\"?utf-8\"?)?", RegexOption.IGNORE_CASE)
    private val CLASS_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,99}")
    private val AREA_TEXT_REJECTS = Regex("[^A-Za-z0-9_+, ]")
    private val FORBIDDEN = setOf("transfer-encoding", "content-encoding", "expect", "upgrade")
    private val SINGLE = setOf("content-length", "content-type")

    /** [head] is the request line and header lines, without the terminating blank line. */
    fun checkHead(head: String, expectedHost: String): HeadCheck {
        val lines = head.split("\r\n")
        if (lines.any { '\r' in it || '\n' in it } || lines.first() != REQUEST_LINE) return reject(invalid(400))
        val headers = mutableMapOf<String, MutableList<String>>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0 || !TOKEN_NAME.matches(line.substring(0, colon))) return reject(invalid(400))
            val value = line.substring(colon + 1).trim(' ', '\t')
            if (value.any { (it < ' ' && it != '\t') || it.code == 0x7F || it.code > 0xFF }) return reject(invalid(400))
            headers.getOrPut(line.substring(0, colon).lowercase()) { mutableListOf() }.add(value)
        }
        if (headers.keys.any { it in FORBIDDEN } || SINGLE.any { (headers[it]?.size ?: 0) > 1 }) {
            return reject(invalid(400))
        }
        val length = headers["content-length"]?.single()
        if (length == null || !length.all { it in '0'..'9' } || length.isEmpty()) return reject(invalid(400))
        // Host/Origin checks are not per-process authentication; they only reject browser-style requests.
        if (headers["host"] != listOf(expectedHost) || "origin" in headers) return reject(forbidden())
        if (headers["content-type"]?.single()?.let(JSON_TYPE::matches) != true) return reject(invalid(415))
        val size = if (length.length > 4) Int.MAX_VALUE else length.toInt()
        if (size > MAX_BODY_BYTES) return reject(invalid(413))
        if (size == 0) return reject(invalid(400))
        return HeadCheck.Accepted(size)
    }

    private fun reject(response: BridgeResponse) = HeadCheck.Rejected(response)

    /** Exactly {"requestId": UUID, "remainingMs": 1..10000, "query": one fixed query}; null otherwise. */
    fun parseBody(body: ByteArray): BridgeRequest? {
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString()
        } catch (_: CharacterCodingException) { return null }
        val fields = FlatJson(text).parseObject() ?: return null
        if (fields.keys != setOf("requestId", "remainingMs", "query")) return null
        val id = fields["requestId"] as? String ?: return null
        val remaining = fields["remainingMs"] as? Long ?: return null
        val query = (fields["query"] as? String)?.let(VehicleQuery::fromWire) ?: return null
        if (!UUID_PATTERN.matches(id) || remaining !in 1..VehicleStatusReader.TIMEOUT_MILLIS) return null
        return BridgeRequest(id, remaining, query)
    }

    /** Every outcome is HTTP 200 with the same envelope; TIMEOUT and CANCELLED have no items. */
    fun result(requestId: String, result: VehicleReadResult): BridgeResponse {
        val json = Json()
        json.field("requestId", requestId)
        json.field("query", result.query.wire)
        json.field("outcome", result.outcome.name)
        json.field("sdkVersion", VEHICLE_SDK_VERSION)
        json.raw("items", result.items.joinToString(",", "[", "]") { item(it) })
        return BridgeResponse(200, json.close())
    }

    private fun item(item: VehicleItem): String = Json().apply {
        field("property", item.key.property.name)
        field("area", item.key.area)
        field("status", item.status.name)
        raw("capability", capability(item.capability))
        raw("rawValue", item.rawValue?.toString() ?: "null")
        field("meaning", item.meaning)
        field("unit", item.unit)
        field("receivedAt", item.receivedAtMillis?.let { Instant.ofEpochMilli(it).toString() })
        field("errorType", item.errorType?.let { if (CLASS_NAME.matches(it)) it else "Exception" })
    }.close()

    private fun capability(capability: ItemCapability?): String = capability?.let {
        Json().apply {
            field("access", it.access.take(MAX_DIAGNOSTIC_CHARS))
            raw("matchingAreas", it.matchingAreas?.toString() ?: "null")
            // Area names are SDK enum names; anything else is replaced so escaping cannot grow the response.
            field("reportedAreas", it.reportedAreas?.replace(AREA_TEXT_REJECTS, "?")?.take(MAX_DIAGNOSTIC_CHARS))
            raw("minimum", number(it.minimum))
            raw("maximum", number(it.maximum))
        }.close()
    } ?: "null"

    private fun number(value: Double?) = if (value == null || !value.isFinite()) "null" else value.toString()

    fun busy(requestId: String) = BridgeResponse(409, admission(requestId, "BUSY"))
    fun notReady(requestId: String) = BridgeResponse(503, admission(requestId, "APP_NOT_READY"))
    fun invalid(status: Int) = BridgeResponse(status, """{"ok":false,"code":"INVALID_REQUEST"}""")
    fun forbidden() = BridgeResponse(403, """{"ok":false,"code":"REQUEST_FORBIDDEN"}""")

    private fun admission(requestId: String, code: String) =
        Json().apply { field("requestId", requestId); raw("ok", "false"); field("code", code) }.close()

    private class Json {
        private val out = StringBuilder("{")
        fun raw(name: String, value: String) {
            if (out.length > 1) out.append(',')
            out.append(quote(name)).append(':').append(value)
        }
        fun field(name: String, value: String?) = raw(name, value?.let(::quote) ?: "null")
        fun close(): String = out.append('}').toString()

        private fun quote(value: String) = buildString {
            append('"')
            for (c in value) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' || c in ' '..' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
            append('"')
        }
    }

    /** Parses one flat object whose values are escape-free strings or integers. */
    private class FlatJson(private val text: String) {
        private var i = 0

        fun parseObject(): Map<String, Any>? {
            val fields = mutableMapOf<String, Any>()
            space()
            if (!take('{')) return null
            space()
            if (!take('}')) {
                do {
                    space()
                    val key = string() ?: return null
                    space()
                    if (!take(':')) return null
                    space()
                    val value: Any = string() ?: integer() ?: return null
                    if (fields.put(key, value) != null) return null
                    space()
                } while (take(','))
                if (!take('}')) return null
            }
            space()
            return if (i == text.length) fields else null
        }

        private fun string(): String? {
            if (!take('"')) return null
            val end = text.indexOf('"', i)
            if (end < 0) return null
            val value = text.substring(i, end)
            if (value.any { it == '\\' || it < ' ' }) return null
            i = end + 1
            return value
        }

        private fun integer(): Long? {
            val start = i
            if (i < text.length && text[i] == '-') i++
            while (i < text.length && text[i] in '0'..'9') i++
            val digits = text.substring(start, i)
            if (i < text.length && text[i] in ".eE") return null
            return if (digits.length in 1..9) digits.toLongOrNull() else null
        }

        private fun take(c: Char): Boolean = (i < text.length && text[i] == c).also { if (it) i++ }
        private fun space() { while (i < text.length && text[i] in " \t\r\n") i++ }
    }
}
