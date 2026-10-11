package com.muse.gadget

import com.muse.gadget.ble.BleFraming
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class BleInteropTest {
    @Test fun `single unframed packet accepted`() {
        val bytes="{\"action\":\"get_device_info\"}".toByteArray()
        assertArrayEquals(bytes,BleFraming.Reassembler().feed(bytes))
    }
    @Test fun `decoder reuses connection across messages`() {
        val decoder=BleFraming.Reassembler()
        repeat(2) {
            val bytes="a long message $it".toByteArray()
            val output=BleFraming.encode(bytes,4).mapNotNull(decoder::feed)
            assertEquals(1,output.size)
            assertArrayEquals(bytes,output.single())
        }
    }
    @Test fun `unsigned chunk count cannot encode 256`() {
        assertThrows(IllegalArgumentException::class.java){BleFraming.encode(ByteArray(256),1)}
    }
}
