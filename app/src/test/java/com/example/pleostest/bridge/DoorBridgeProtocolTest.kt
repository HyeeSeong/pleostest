package com.example.pleostest.bridge

import com.example.pleostest.vehicle.DOOR_AREAS
import com.example.pleostest.vehicle.ItemCapability
import com.example.pleostest.vehicle.ItemStatus
import com.example.pleostest.vehicle.VehicleItem
import com.example.pleostest.vehicle.VehicleItemKey
import com.example.pleostest.vehicle.VehicleOutcome
import com.example.pleostest.vehicle.VehicleProperty
import com.example.pleostest.vehicle.VehicleQuery
import com.example.pleostest.vehicle.VehicleReadResult
import org.junit.Assert.*
import org.junit.Test

class DoorBridgeProtocolTest {
    private val host = "127.0.0.1:18765"
    private val id = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private val line = "POST /v2/vehicle-read HTTP/1.1"
    private val doorCapability = ItemCapability("READ", 1, "ROW_1_LEFT", 0.0, 1.0)
    private val frontLeft = VehicleItemKey(VehicleProperty.DOOR_POSITION, "ROW_1_LEFT")

    private fun head(vararg headers: String, requestLine: String = line) =
        (listOf(requestLine) + headers).joinToString("\r\n")

    private fun valid(vararg extra: String, requestLine: String = line) = head(
        "Host: $host", "Content-Type: application/json",
        "Content-Length: 60", *extra, requestLine = requestLine,
    )

    private fun check(head: String) = DoorBridgeProtocol.checkHead(head, host)

    private fun rejected(head: String): BridgeResponse =
        (check(head) as HeadCheck.Rejected).response

    @Test fun validHeadIsAcceptedWithItsBodyLength() {
        assertEquals(HeadCheck.Accepted(60), check(valid()))
        assertEquals(HeadCheck.Accepted(60), check(valid("Connection: close", "User-Agent: node")))
        assertEquals(HeadCheck.Accepted(9), check(head("host: $host",
            "content-type: application/json; charset=UTF-8", "content-length: 9")))
    }

    @Test fun requestWithoutAnyAuthorizationHeaderIsAccepted() {
        assertEquals(HeadCheck.Accepted(60), check(valid()))
        // A leftover Authorization header from an older client is ignored, not an authentication mode.
        assertEquals(HeadCheck.Accepted(60), check(valid("Authorization: Bearer anything")))
        assertEquals(HeadCheck.Accepted(60), check(valid("Authorization: a", "Authorization: b")))
    }

    @Test fun originOrBadHostIsForbidden() {
        for (h in listOf(valid("Origin: http://127.0.0.1:18765"),
            valid("Origin: a", "Origin: b"),
            head("Host: localhost:18765", "Content-Type: application/json", "Content-Length: 60"),
            head("Host: 127.0.0.1:18766", "Content-Type: application/json", "Content-Length: 60"),
            head("Content-Type: application/json", "Content-Length: 60"))) {
            val response = rejected(h)
            assertEquals(h, 403, response.status)
            assertEquals("""{"ok":false,"code":"REQUEST_FORBIDDEN"}""", response.json)
        }
    }

    @Test fun ambiguousFramingAndTheRetiredV1RouteAreInvalid() {
        val framing = listOf(
            valid(requestLine = "POST /v1/door-position HTTP/1.1"),
            valid(requestLine = "GET /v2/vehicle-read HTTP/1.1"),
            valid(requestLine = "POST /v2/other HTTP/1.1"),
            valid(requestLine = "POST http://127.0.0.1:18765/v2/vehicle-read HTTP/1.1"),
            valid(requestLine = "POST /v2/vehicle-read HTTP/1.0"),
            valid(requestLine = "POST /v2/vehicle-read?x=1 HTTP/1.1"),
            valid(" folded"),
            valid("Content-Length: 60"),
            valid("Content-Type: application/json"),
            valid("Transfer-Encoding: chunked"),
            valid("Content-Encoding: gzip"),
            valid("Expect: 100-continue"),
            valid("Upgrade: h2c"),
            valid("Bad Header: x"),
            valid().replace("\r\nContent-Length", "\nContent-Length"),
            valid().replace("Content-Length: 60", "Content-Length: +60"),
            valid().replace("Content-Length: 60", "Content-Length: 6 0"),
            valid().replace("Content-Length: 60", "Content-Length: 0"),
            head("Host: $host", "Content-Type: application/json"),
        )
        for (h in framing) {
            val response = rejected(h)
            assertEquals(h, 400, response.status)
            assertEquals("""{"ok":false,"code":"INVALID_REQUEST"}""", response.json)
        }
    }

    @Test fun wrongMediaTypeAndOversizedBodyHaveDistinctStatuses() {
        for (type in listOf("text/plain", "application/json; charset=latin1", "application/jsonx")) {
            assertEquals(415, rejected(valid().replace("application/json", type)).status)
        }
        assertEquals(413, rejected(valid().replace("Content-Length: 60", "Content-Length: 1025")).status)
        assertEquals(413, rejected(valid().replace("Content-Length: 60", "Content-Length: 99999999999")).status)
        assertEquals(HeadCheck.Accepted(1024), check(valid().replace("Content-Length: 60", "Content-Length: 1024")))
    }

    @Test fun bodyMustBeExactlyRequestIdBudgetAndAFixedQuery() {
        assertEquals(BridgeRequest(id, 10_000, VehicleQuery.DOOR_STATUS),
            parse("""{"requestId":"$id","remainingMs":10000,"query":"door_status"}"""))
        assertEquals(BridgeRequest(id, 1, VehicleQuery.FRONT_LEFT_DOOR_POSITION),
            parse(""" { "query" : "front_left_door_position", "remainingMs" : 1 , "requestId" : "$id" } """))
        for (query in VehicleQuery.entries) {
            assertEquals(query, parse("""{"requestId":"$id","remainingMs":5,"query":"${query.wire}"}""")?.query)
        }
        for (body in listOf(
            """{"requestId":"$id","remainingMs":10}""",
            """{"requestId":"$id","query":"door_status"}""",
            """{"remainingMs":10,"query":"door_status"}""",
            """{"requestId":"$id","remainingMs":10,"query":"door_status","area":"ROW_1_RIGHT"}""",
            """{"requestId":"$id","remainingMs":10,"query":"door_status","timeoutMs":5}""",
            """{"requestId":"$id","remainingMs":10,"query":"door_status","query":"door_status"}""",
            """{"requestId":"$id","remainingMs":10,"query":"DOOR_STATUS"}""",
            """{"requestId":"$id","remainingMs":10,"query":"vehicle_speed"}""",
            """{"requestId":"$id","remainingMs":10,"query":"door_status "}""",
            """{"requestId":"$id","remainingMs":10,"query":""}""",
            """{"requestId":"$id","remainingMs":10,"query":null}""",
            """{"requestId":"$id","remainingMs":10,"query":5}""",
            """{"requestId":"$id","remainingMs":0,"query":"door_status"}""",
            """{"requestId":"$id","remainingMs":10001,"query":"door_status"}""",
            """{"requestId":"$id","remainingMs":1.5,"query":"door_status"}""",
            """{"requestId":"$id","remainingMs":"10","query":"door_status"}""",
            """{"requestId":"$id","remainingMs":-1,"query":"door_status"}""",
            """{"requestId":"$id","remainingMs":99999999999999999999,"query":"door_status"}""",
            """{"requestId":"not-a-uuid","remainingMs":10,"query":"door_status"}""",
            """{"requestId":null,"remainingMs":10,"query":"door_status"}""",
            """{"requestId":"$id","remainingMs":10,"query":"door_status"}x""",
            """[{"requestId":"$id","remainingMs":10,"query":"door_status"}]""",
            "null", "", "{}",
        )) {
            assertNull(body, parse(body))
        }
        assertNull(DoorBridgeProtocol.parseBody(byteArrayOf(0xC3.toByte(), 0x28)))
    }

    private fun parse(body: String) = DoorBridgeProtocol.parseBody(body.toByteArray())

    private fun ok(key: VehicleItemKey, raw: Any?, meaning: String?, unit: String? = null,
                   capability: ItemCapability = doorCapability, at: Long = 0L) =
        VehicleItem(key, ItemStatus.OK, capability, raw, meaning, unit, at)

    @Test fun frontLeftSuccessKeepsOriginalEvidenceInOneExactItem() {
        val closed = DoorBridgeProtocol.result(id, VehicleReadResult(VehicleQuery.FRONT_LEFT_DOOR_POSITION,
            VehicleOutcome.COMPLETE, listOf(ok(frontLeft, 0, "closed"))))
        assertEquals(200, closed.status)
        assertEquals("""{"requestId":"$id","query":"front_left_door_position","outcome":"COMPLETE",""" +
            """"sdkVersion":"2.3.2","items":[{"property":"DOOR_POSITION","area":"ROW_1_LEFT","status":"OK",""" +
            """"capability":{"access":"READ","matchingAreas":1,"reportedAreas":"ROW_1_LEFT",""" +
            """"minimum":0.0,"maximum":1.0},"rawValue":0,"meaning":"closed","unit":null,""" +
            """"receivedAt":"1970-01-01T00:00:00Z","errorType":null}]}""", closed.json)
        val open = DoorBridgeProtocol.result(id, VehicleReadResult(VehicleQuery.FRONT_LEFT_DOOR_POSITION,
            VehicleOutcome.COMPLETE, listOf(ok(frontLeft, 1, "open", at = 1_500L))))
        assertTrue(open.json.contains(""""rawValue":1,"meaning":"open","unit":null,"receivedAt":"1970-01-01T00:00:01.500Z""""))
    }

    @Test fun scalarsBooleansAndPercentsKeepTheirOwnTypesAndUnits() {
        val fuel = VehicleItemKey(VehicleProperty.FUEL_PERCENT, null)
        val lock = VehicleItemKey(VehicleProperty.DOOR_LOCK, "ROW_2_RIGHT")
        val scalar = ItemCapability("READ_WRITE", null, null, null, null)
        val response = DoorBridgeProtocol.result(id, VehicleReadResult(VehicleQuery.ENERGY_LEVEL, VehicleOutcome.PARTIAL, listOf(
            ok(fuel, 63, null, "%", scalar),
            VehicleItem(VehicleItemKey(VehicleProperty.EV_BATTERY_PERCENT, null), ItemStatus.READ_UNAVAILABLE,
                ItemCapability("NONE", null, null, null, null)))))
        assertTrue(response.json, response.json.contains(""""property":"FUEL_PERCENT","area":null,"status":"OK","capability":""" +
            """{"access":"READ_WRITE","matchingAreas":null,"reportedAreas":null,"minimum":null,"maximum":null},""" +
            """"rawValue":63,"meaning":null,"unit":"%","receivedAt":"1970-01-01T00:00:00Z""""))
        assertTrue(response.json.contains(""""status":"READ_UNAVAILABLE","capability":{"access":"NONE""""))
        assertTrue(response.json.contains(""""rawValue":null,"meaning":null,"unit":null,"receivedAt":null,"errorType":null}]}"""))
        val locked = DoorBridgeProtocol.result(id, VehicleReadResult(VehicleQuery.DOOR_STATUS, VehicleOutcome.PARTIAL,
            listOf(ok(lock, true, "locked", capability = ItemCapability("READ", 1, "ROW_2_RIGHT", null, null)))))
        assertTrue(locked.json.contains(""""rawValue":true,"meaning":"locked""""))
    }

    @Test fun failuresKeepObservationAndReasonWithoutAValue() {
        val noValue = DoorBridgeProtocol.result(id, VehicleReadResult(VehicleQuery.FRONT_LEFT_DOOR_POSITION,
            VehicleOutcome.FAILED, listOf(VehicleItem(frontLeft, ItemStatus.NO_VALUE, doorCapability, null, null, null, 0L))))
        assertEquals("""{"requestId":"$id","query":"front_left_door_position","outcome":"FAILED",""" +
            """"sdkVersion":"2.3.2","items":[{"property":"DOOR_POSITION","area":"ROW_1_LEFT","status":"NO_VALUE",""" +
            """"capability":{"access":"READ","matchingAreas":1,"reportedAreas":"ROW_1_LEFT","minimum":0.0,"maximum":1.0},""" +
            """"rawValue":null,"meaning":null,"unit":null,"receivedAt":"1970-01-01T00:00:00Z","errorType":null}]}""", noValue.json)

        val unknown = DoorBridgeProtocol.result(id, VehicleReadResult(VehicleQuery.FRONT_LEFT_DOOR_POSITION,
            VehicleOutcome.FAILED, listOf(VehicleItem(frontLeft, ItemStatus.UNKNOWN_VALUE,
                doorCapability.copy(maximum = 100.0), 0, null, null, 0L))))
        assertTrue(unknown.json.contains(""""status":"UNKNOWN_VALUE""""))
        assertTrue(unknown.json.contains(""""rawValue":0,"meaning":null"""))

        val failed = DoorBridgeProtocol.result(id, VehicleReadResult(VehicleQuery.FRONT_LEFT_DOOR_POSITION,
            VehicleOutcome.FAILED, listOf(VehicleItem(frontLeft, ItemStatus.CAPABILITY_FAILED, errorType = "SecurityException"))))
        assertTrue(failed.json.contains(""""status":"CAPABILITY_FAILED","capability":null,"rawValue":null,""" +
            """"meaning":null,"unit":null,"receivedAt":null,"errorType":"SecurityException"}]}"""))
    }

    @Test fun timeoutAndCancelCarryNoItemsAtAll() {
        for (outcome in listOf(VehicleOutcome.TIMEOUT, VehicleOutcome.CANCELLED)) {
            val response = DoorBridgeProtocol.result(id, VehicleReadResult(VehicleQuery.DOOR_STATUS, outcome))
            assertEquals(200, response.status)
            assertEquals("""{"requestId":"$id","query":"door_status","outcome":"${outcome.name}",""" +
                """"sdkVersion":"2.3.2","items":[]}""", response.json)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VehicleReadResult(VehicleQuery.DOOR_STATUS, VehicleOutcome.TIMEOUT, listOf(
                VehicleItem(frontLeft, ItemStatus.OK, doorCapability, 0, "closed", null, 0L)))
        }
    }

    @Test fun diagnosticsAreBoundedAndNonFiniteBoundsBecomeNull() {
        val noisy = ItemCapability("READ", 0, "\"x\"\n".repeat(1_000), Double.NaN, Double.POSITIVE_INFINITY)
        val response = DoorBridgeProtocol.result(id, VehicleReadResult(VehicleQuery.FRONT_LEFT_DOOR_POSITION,
            VehicleOutcome.FAILED, listOf(VehicleItem(frontLeft, ItemStatus.SUPPORT_UNCONFIRMED, noisy,
                errorType = "bad name: secret"))))
        assertTrue(response.json.contains(""""minimum":null,"maximum":null"""))
        assertTrue(response.json.contains(""""errorType":"Exception""""))
        assertFalse(response.json.contains("secret"))
        // Area names come from SDK enum names; anything else is replaced so escaping cannot grow the response.
        assertTrue(response.json.contains(""""reportedAreas":"?x??"""))
        assertFalse(response.json.contains("\\"))
        assertTrue(response.toBytes().size < 16 * 1024)
    }

    @Test fun eightDoorItemsFitTheSixteenKibResponseLimitEvenWithNoisyDiagnostics() {
        val areas = DOOR_AREAS.joinToString(", ")
        val items = DOOR_AREAS.map { area ->
            ok(VehicleItemKey(VehicleProperty.DOOR_POSITION, area), 0, "closed", capability = doorCapability.copy(reportedAreas = areas),
                at = 1_700_000_000_123L)
        } + DOOR_AREAS.map { area ->
            ok(VehicleItemKey(VehicleProperty.DOOR_LOCK, area), false, "unlocked",
                capability = ItemCapability("READ_WRITE", 1, areas, null, null), at = 1_700_000_000_123L)
        }
        val normal = DoorBridgeProtocol.result(id, VehicleReadResult(VehicleQuery.DOOR_STATUS, VehicleOutcome.COMPLETE, items))
        assertTrue(normal.toBytes().size < 4 * 1024)
        val noisy = DoorBridgeProtocol.result(id, VehicleReadResult(VehicleQuery.DOOR_STATUS, VehicleOutcome.COMPLETE,
            items.map { it.copy(capability = it.capability!!.copy(reportedAreas = "\u0001".repeat(600))) }))
        assertTrue(noisy.toBytes().size.toString(), noisy.toBytes().size < 16 * 1024)
    }

    @Test fun rejectionsNeverReflectUnacceptedIdsButAdmissionReplies() {
        assertEquals(409, DoorBridgeProtocol.busy(id).status)
        assertEquals("""{"requestId":"$id","ok":false,"code":"BUSY"}""", DoorBridgeProtocol.busy(id).json)
        assertEquals(503, DoorBridgeProtocol.notReady(id).status)
        assertEquals("""{"requestId":"$id","ok":false,"code":"APP_NOT_READY"}""", DoorBridgeProtocol.notReady(id).json)
        assertEquals("""{"ok":false,"code":"INVALID_REQUEST"}""", DoorBridgeProtocol.invalid(400).json)
    }

    @Test fun httpResponseDeclaresLengthAndClose() {
        val bytes = DoorBridgeProtocol.busy(id).toBytes()
        val text = String(bytes, Charsets.UTF_8)
        val body = DoorBridgeProtocol.busy(id).json
        assertTrue(text.startsWith("HTTP/1.1 409 Conflict\r\n"))
        assertTrue(text.contains("\r\nContent-Type: application/json; charset=utf-8\r\n"))
        assertTrue(text.contains("\r\nContent-Length: ${body.toByteArray().size}\r\n"))
        assertTrue(text.contains("\r\nConnection: close\r\n"))
        assertTrue(text.endsWith("\r\n\r\n$body"))
    }
}
