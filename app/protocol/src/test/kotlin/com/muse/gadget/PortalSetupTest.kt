package com.muse.gadget
import com.muse.gadget.pairing.*
import com.muse.gadget.ble.BleFraming
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PortalSetupTest {
    private fun setup()=PortalSetup(PairingSession("homelink-123456","hatch-link:02:00:00:12:34:56","02:00:00:12:34:56","0.2.0"),
        "homelink-123456",{true},{_,_->fail<Unit>("Plaintext must never provision")})
    @Test fun `public device info includes routing fields`() {
        val value=setup().handle("""{"action":"get_device_info"}""").single()
        assertTrue(value.contains("device_info"));assertTrue(value.contains("homelink-123456"));assertTrue(value.contains("network_ready"))
    }
    @Test fun `plaintext provisioning and wifi actions are refused`() {
        for(action in listOf("provision_v2","wifi_scan","set_auth","unpair","device.ota"))
            assertEquals(listOf("error_encryption_required"),setup().handle("""{"action":"$action"}"""))
    }
    @Test fun `official framing permits unframed json and consecutive messages`() {
        val r=BleFraming.Reassembler()
        val json="""{"action":"get_device_info"}""".toByteArray()
        assertArrayEquals(json,r.feed(json))
        repeat(2){var result:ByteArray?=null;BleFraming.encode(json,8).forEach{result=r.feed(it)};assertArrayEquals(json,result)}
    }
    @Test fun `256 chunks cannot be encoded in unsigned byte count`() {
        assertThrows(IllegalArgumentException::class.java){BleFraming.encode(ByteArray(256),1)}
    }
}
