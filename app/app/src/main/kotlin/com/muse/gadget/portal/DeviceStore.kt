package com.muse.gadget.portal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private state, encrypted by an Android Keystore key; no logs/backups. */
class DeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences("portal-device", Context.MODE_PRIVATE)
    private val alias = "muse-portal-device-v1"
    val mac: String
    val nodeId: String
    init {
        mac = prefs.getString("identity", null) ?: ByteArray(6).also {
            SecureRandom().nextBytes(it); it[0] = ((it[0].toInt() and 0xfc) or 2).toByte()
        }.joinToString(":") { "%02x".format(it.toInt() and 255) }.also {
            check(prefs.edit().putString("identity", it).commit())
        }
        nodeId = "homelink-" + mac.replace(":", "").takeLast(6)
    }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    @Synchronized fun save(name: String, value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray())
        val sealed = cipher.doFinal(value.toString().toByteArray())
        check(prefs.edit().putString(name, Base64.encodeToString(cipher.iv+sealed, Base64.NO_WRAP)).commit())
    }
    @Synchronized fun read(name: String): JSONObject? {
        val stored = prefs.getString(name, null) ?: return null
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0,12)))
        cipher.updateAAD(name.toByteArray())
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size))))
    }
}
