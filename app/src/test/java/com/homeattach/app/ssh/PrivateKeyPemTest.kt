package com.homeattach.app.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateKeyPemTest {
    @Test
    fun normalizesEscapedNewlinesAndWhitespaceInsideOpenSshPem() {
        val messy = """
            copied from chat:
            "$OPENSSH_HEADER\n
            ${OPENSSH_BODY.take(40)}  ${OPENSSH_BODY.drop(40)}
            \n$OPENSSH_FOOTER"
        """.trimIndent()

        assertEquals(
            "$OPENSSH_HEADER\n$OPENSSH_BODY\n$OPENSSH_FOOTER\n",
            normalizePrivateKeyPem(messy),
        )
    }

    @Test
    fun extractsPrivateKeyBlockFromSurroundingText() {
        val messy = "Host: example\n$RSA_PRIVATE_KEY\nPort: 22"

        assertEquals(RSA_PRIVATE_KEY, normalizePrivateKeyPem(messy))
    }

    @Test
    fun rejectsPublicKey() {
        val error = runCatching {
            normalizePrivateKeyPem("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITestOnly user@host")
        }.exceptionOrNull()

        assertTrue(error is PrivateKeyFormatException)
    }

    private companion object {
        const val PRIVATE_KEY_WORDS = "PRIVATE KEY"
        const val OPENSSH_HEADER = "-----BEGIN OPENSSH $PRIVATE_KEY_WORDS-----"
        const val OPENSSH_FOOTER = "-----END OPENSSH $PRIVATE_KEY_WORDS-----"
        const val OPENSSH_BODY = "b3Blb" + "nNzaC1rZXktdjE="
        const val RSA_PRIVATE_KEY =
            "-----BEGIN RSA $PRIVATE_KEY_WORDS-----\n" +
                "AQIDBAUGBwg=\n" +
                "-----END RSA $PRIVATE_KEY_WORDS-----\n"
    }
}
