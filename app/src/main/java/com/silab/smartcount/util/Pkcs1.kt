package com.silab.smartcount.util

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

/**
 * La API de Tricount espera la clave pública en formato PKCS#1
 * ("-----BEGIN RSA PUBLIC KEY-----"), no en el X.509/SPKI que devuelve
 * Java por defecto. Aquí generamos el DER de PKCS#1 a mano:
 *
 *   RSAPublicKey ::= SEQUENCE { modulus INTEGER, publicExponent INTEGER }
 *
 * Así evitamos arrastrar BouncyCastle solo para esto.
 */
object Pkcs1 {

    fun generateKeyPairPem(): String {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val pub = gen.generateKeyPair().public as RSAPublicKey
        return toPem(derSequence(derInteger(pub.modulus), derInteger(pub.publicExponent)))
    }

    private fun toPem(der: ByteArray): String {
        val b64 = Base64.encodeToString(der, Base64.NO_WRAP)
        val body = b64.chunked(64).joinToString("\n")
        return "-----BEGIN RSA PUBLIC KEY-----\n$body\n-----END RSA PUBLIC KEY-----\n"
    }

    private fun derInteger(value: BigInteger): ByteArray {
        // BigInteger.toByteArray() ya devuelve complemento a dos con signo,
        // que es exactamente lo que pide DER para INTEGER.
        return tlv(0x02, value.toByteArray())
    }

    private fun derSequence(vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        parts.forEach { body.write(it) }
        return tlv(0x30, body.toByteArray())
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        out.write(encodeLength(content.size))
        out.write(content)
        return out.toByteArray()
    }

    private fun encodeLength(len: Int): ByteArray {
        if (len < 0x80) return byteArrayOf(len.toByte())
        var n = len
        val bytes = ArrayList<Byte>()
        while (n > 0) {
            bytes.add(0, (n and 0xFF).toByte())
            n = n ushr 8
        }
        return ByteArray(bytes.size + 1) { i ->
            if (i == 0) (0x80 or bytes.size).toByte() else bytes[i - 1]
        }
    }
}
