package com.muse.gadget.pairing

import com.muse.gadget.util.*

/** Serialized BLE setup routing; no Wi-Fi changes, shell, filesystem or OTA commands. */
class PortalSetup(
    private val pairing: PairingSession,
    private val nodeId: String,
    private val online: () -> Boolean,
    private val provision: (JsonValue.Obj, Long) -> Unit,
    private val wifiSsid: () -> String? = { null },
) {
    private var plaintextBlocked = false
    fun disconnected() { pairing.reset(); plaintextBlocked = false }
    fun handle(text: String, encrypted: Boolean = false): List<String> {
        val obj = Json.parseObj(text)
        val action = obj.strField("action") ?: ""
        if (!encrypted && action == "get_device_info") {
            val info = Json.parseObj(pairing.deviceInfoJson())
            return listOf(Json.stringify(Json.obj(
                *info.fields.map { it.key to it.value }.toTypedArray(),
                "type" to Json.str("device_info"), "node_id" to Json.str(nodeId),
                "version" to Json.str("0.2.0"), "build_sha" to Json.str(""),
                "network_ready" to Json.bool(online()),
            )))
        }
        if (!encrypted && action == "pairing_client_hello") {
            val ready = pairing.handleHello(text)
            plaintextBlocked = true
            return listOf(ready)
        }
        if (!encrypted && action == "pairing_encrypted")
            return handle(pairing.decryptEnvelope(text), true)
        if (!encrypted) return if (plaintextBlocked) emptyList() else listOf("error_encryption_required")
        if (action == "pairing_client_finished") {
            val generation = pairing.handleClientFinished(text)
            require(generation != 0L) { "pairing confirmation failed" }
            return listOfNotNull(pairing.encryptStatus("pairing_confirmed", generation))
        }
        if (!pairing.confirmed) return listOfNotNull(pairing.encryptStatus("error_pairing_confirm_required"))
        return when (action) {
            "wifi_scan" -> listOfNotNull(pairing.encryptJson(Json.stringify(Json.obj(
                "type" to Json.str("wifi_scan_result"),
                "networks" to JsonValue.Arr(if (online() && !wifiSsid().isNullOrBlank()) listOf(Json.obj(
                    "ssid" to Json.str(wifiSsid()!!), "rssi" to Json.num(-40),
                    "secure" to Json.bool(false))) else emptyList())
            ))))
            "provision_v2" -> {
                if (obj.strField("access_token").isNullOrBlank() ||
                    obj.strField("refresh_token").isNullOrBlank() || obj.strField("token_type") != "device")
                    return listOfNotNull(pairing.encryptStatus("error_missing_credentials"))
                val gen = pairing.markProvisioning()
                require(gen != 0L)
                // Deliberately exclude SSID/password and unrelated account fields.
                provision(Json.obj(*listOf("access_token", "refresh_token", "api_url_v2", "noise_host")
                    .map { it to Json.str(obj.strField(it) ?: "") }.toTypedArray()), gen)
                listOfNotNull(pairing.encryptStatus("wifi_connected", gen))
            }
            else -> listOfNotNull(pairing.encryptStatus("error_unknown_action"))
        }
    }
}
