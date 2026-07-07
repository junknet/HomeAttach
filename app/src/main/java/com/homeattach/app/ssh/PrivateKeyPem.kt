package com.homeattach.app.ssh

import java.util.Base64
import java.util.Locale

class PrivateKeyFormatException(message: String) : IllegalArgumentException(message)

fun normalizePrivateKeyPem(rawPrivateKey: String): String {
    val text = rawPrivateKey
        .replace("\\r\\n", "\n")
        .replace("\\n", "\n")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .removeInvisibleCopyArtifacts()
        .trim()
        .trim('"', '\'', '`')
        .trim()

    if (text.isBlank()) {
        throw PrivateKeyFormatException("Private key is empty")
    }
    if (PUBLIC_KEY_PREFIXES.any { text.startsWith(it) }) {
        throw PrivateKeyFormatException("Paste the private key, not the public key")
    }

    val headerMatch = PEM_HEADER_REGEX.find(text)
        ?: throw PrivateKeyFormatException("Missing BEGIN private key header")
    val keyType = headerMatch.groupValues[1].normalizePemType()
    val footerRegex = Regex(
        "-----\\s*END\\s+${Regex.escape(keyType)}\\s*-----",
        RegexOption.IGNORE_CASE,
    )
    val footerMatch = footerRegex.find(text, headerMatch.range.last + 1)
        ?: throw PrivateKeyFormatException("Missing END private key footer")

    val body = text.substring(headerMatch.range.last + 1, footerMatch.range.first)
        .filterNot(Char::isWhitespace)
    if (body.isBlank()) {
        throw PrivateKeyFormatException("Private key body is empty")
    }
    if (!BASE64_BODY_REGEX.matches(body)) {
        throw PrivateKeyFormatException("Private key body contains non-base64 characters")
    }
    try {
        Base64.getDecoder().decode(body.withBase64Padding())
    } catch (e: IllegalArgumentException) {
        throw PrivateKeyFormatException("Private key body is not valid base64")
    }

    return buildString {
        append("-----BEGIN ")
        append(keyType)
        append("-----\n")
        body.chunked(PEM_LINE_LENGTH).forEach { line ->
            append(line)
            append('\n')
        }
        append("-----END ")
        append(keyType)
        append("-----\n")
    }
}

private fun String.removeInvisibleCopyArtifacts(): String =
    replace("\uFEFF", "")
        .replace("\u200B", "")
        .replace("\u200C", "")
        .replace("\u200D", "")
        .replace("\u2060", "")

private fun String.normalizePemType(): String =
    trim()
        .replace(Regex("\\s+"), " ")
        .uppercase(Locale.US)

private fun String.withBase64Padding(): String {
    val remainder = length % 4
    if (remainder == 0) return this
    if (remainder == 1) {
        throw IllegalArgumentException("Invalid base64 length")
    }
    return this + "=".repeat(4 - remainder)
}

private const val PEM_LINE_LENGTH = 70
private val PEM_HEADER_REGEX = Regex(
    "-----\\s*BEGIN\\s+([A-Z0-9 ]*PRIVATE KEY)\\s*-----",
    RegexOption.IGNORE_CASE,
)
private val BASE64_BODY_REGEX = Regex("[A-Za-z0-9+/=]+")
private val PUBLIC_KEY_PREFIXES = listOf(
    "ssh-ed25519 ",
    "ssh-rsa ",
    "ecdsa-sha2-",
)
