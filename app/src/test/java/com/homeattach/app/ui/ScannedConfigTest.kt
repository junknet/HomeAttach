package com.homeattach.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks the QR payload contract between `server/tsess-qr-config` (generator) and
 * [parseScannedConfig] (consumer): a YAML document whose `private_key` block scalar must
 * survive byte-for-byte, plus the legacy JSON payload which parses as a YAML subset.
 */
class ScannedConfigTest {

    private val pem = """
        -----BEGIN OPENSSH PRIVATE KEY-----
        b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
        QyNTUxOQAAACBFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAKEFAK
        -----END OPENSSH PRIVATE KEY-----
    """.trimIndent()

    /** Exactly what tsess-qr-config emits: scalars + `private_key: |` with 2-space indent. */
    private fun serverYaml(host: String) = buildString {
        append("host: ").append(host).append('\n')
        append("port: 22\n")
        append("username: junknet\n")
        append("private_key: |\n")
        pem.lines().forEach { append("  ").append(it).append('\n') }
    }

    @Test
    fun `parses the server-generated YAML payload with exact key round-trip`() {
        val parsed = parseScannedConfig(serverYaml("192.168.0.50"))!!
        assertEquals("192.168.0.50", parsed.host)
        assertEquals(22, parsed.port)
        assertEquals("junknet", parsed.username)
        // The shell's $(...) strips the key file's trailing newline, so the document — and
        // hence the parsed scalar — ends without one. The PEM body itself must be untouched;
        // normalizePrivateKeyPem re-adds final framing downstream.
        assertEquals(pem, parsed.privateKey)
    }

    @Test
    fun `parses an IPv6 host scalar`() {
        val parsed = parseScannedConfig(serverYaml("2001:db8::beef"))!!
        assertEquals("2001:db8::beef", parsed.host)
    }

    @Test
    fun `legacy JSON payload parses as a YAML subset`() {
        val json = """{"host":"10.0.0.2","port":2222,"username":"agy","privateKey":"$KEY_ONE_LINE"}"""
        val parsed = parseScannedConfig(json)!!
        assertEquals("10.0.0.2", parsed.host)
        assertEquals(2222, parsed.port)
        assertEquals("agy", parsed.username)
        assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----\n", parsed.privateKey)
    }

    @Test
    fun `garbage payload returns null instead of throwing`() {
        assertNull(parseScannedConfig("not a config at all"))
        assertNull(parseScannedConfig(""))
        assertNull(parseScannedConfig("[1, 2, 3]"))
    }

    private companion object {
        const val KEY_ONE_LINE =
            "-----BEGIN OPENSSH PRIVATE KEY-----\\nabc\\n-----END OPENSSH PRIVATE KEY-----\\n"
    }
}
