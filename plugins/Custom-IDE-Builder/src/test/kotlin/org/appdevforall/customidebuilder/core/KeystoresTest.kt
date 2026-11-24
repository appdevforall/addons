package org.appdevforall.customidebuilder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.KeyStore

class KeystoresTest {
    private val material = TestSupport.material()

    private fun open(bytes: ByteArray, password: String) = Keystores.open(bytes, password.toCharArray())

    @Test
    fun readsPkcs12() {
        val result = open(TestSupport.keystoreBytes("PKCS12", material, "storepw"), "storepw")
        result as KeystoreOpen.Opened
        assertEquals("PKCS12", result.type)
        assertEquals(listOf("key"), result.keyAliases)
    }

    @Test
    fun readsBks() {
        val bytes = TestSupport.keystoreBytes("BKS", material, "storepw")
        assertEquals(KeystoreFormat.BKS_LIKELY, KeystoreFormats.sniff(bytes))
        val result = open(bytes, "storepw")
        result as KeystoreOpen.Opened
        assertEquals("BKS", result.type)
        assertEquals(listOf("key"), result.keyAliases)
    }

    @Test
    fun rejectsJksWithTheConversionCommand() {
        val store = KeyStore.getInstance("JKS").apply { load(null, null) }
        store.setKeyEntry("key", material.key, "pw".toCharArray(), arrayOf(material.cert))
        val bytes = ByteArrayOutputStream().also { store.store(it, "pw".toCharArray()) }.toByteArray()
        assertEquals(KeystoreFormat.JKS, KeystoreFormats.sniff(bytes))
        val result = open(bytes, "pw")
        result as KeystoreOpen.Rejected
        assertTrue(result.message, result.message.contains("keytool -importkeystore -srckeystore old.jks -srcstoretype JKS -destkeystore new.p12 -deststoretype PKCS12"))
    }

    @Test
    fun rejectsJceks() {
        val store = KeyStore.getInstance("JCEKS").apply { load(null, null) }
        val bytes = ByteArrayOutputStream().also { store.store(it, "pw".toCharArray()) }.toByteArray()
        assertEquals(KeystoreFormat.JCEKS, KeystoreFormats.sniff(bytes))
        assertTrue(open(bytes, "pw") is KeystoreOpen.Rejected)
    }

    @Test
    fun aWrongStorePasswordIsToldApartFromADamagedFile() {
        assertEquals(KeystoreOpen.WrongPassword, open(TestSupport.keystoreBytes("PKCS12", material, "right"), "wrong"))
        assertEquals(KeystoreOpen.WrongPassword, open(TestSupport.keystoreBytes("BKS", material, "right"), "wrong"))
        assertTrue(open(ByteArray(200) { 7 }, "x") is KeystoreOpen.Rejected)
        assertTrue(open(ByteArray(2), "x") is KeystoreOpen.Rejected)
    }

    @Test
    fun loadsTheKeyAndChain() {
        val opened = open(TestSupport.keystoreBytes("PKCS12", material, "pw"), "pw") as KeystoreOpen.Opened
        val loaded = Keystores.loadKey(opened, "key", CharArray(0), "pw".toCharArray()) as KeyLoad.Loaded
        assertEquals(material.cert, loaded.key.certificate)
        assertEquals(95, loaded.key.fingerprint.length)
    }

    @Test
    fun aBlankKeyPasswordMeansTheStorePasswordAndADifferentOneIsHonoured() {
        val bytes = TestSupport.keystoreBytes("BKS", material, "store", keyPassword = "keypw")
        val opened = open(bytes, "store") as KeystoreOpen.Opened
        assertTrue(Keystores.loadKey(opened, "key", "keypw".toCharArray(), "store".toCharArray()) is KeyLoad.Loaded)
        val wrong = Keystores.loadKey(opened, "key", CharArray(0), "store".toCharArray())
        assertTrue(wrong is KeyLoad.Failed)
        assertTrue((wrong as KeyLoad.Failed).message.contains("key password"))
    }

    @Test
    fun anUnknownAliasFails() {
        val opened = open(TestSupport.keystoreBytes("PKCS12", material, "pw"), "pw") as KeystoreOpen.Opened
        assertTrue(Keystores.loadKey(opened, "nope", CharArray(0), "pw".toCharArray()) is KeyLoad.Failed)
    }

    @Test
    fun sniffsHeaders() {
        assertEquals(KeystoreFormat.PKCS12_LIKELY, KeystoreFormats.sniff(byteArrayOf(0x30, -126, 1, 2)))
        assertEquals(KeystoreFormat.BKS_LIKELY, KeystoreFormats.sniff(byteArrayOf(0, 0, 0, 2)))
        assertEquals(KeystoreFormat.BKS_LIKELY, KeystoreFormats.sniff(byteArrayOf(0, 0, 0, 1)))
        assertEquals(KeystoreFormat.JKS, KeystoreFormats.sniff(byteArrayOf(-2, -19, -2, -19)))
        assertEquals(KeystoreFormat.UNKNOWN, KeystoreFormats.sniff(byteArrayOf(1, 2, 3, 4)))
        assertEquals(KeystoreFormat.UNKNOWN, KeystoreFormats.sniff(byteArrayOf(1)))
    }
}
