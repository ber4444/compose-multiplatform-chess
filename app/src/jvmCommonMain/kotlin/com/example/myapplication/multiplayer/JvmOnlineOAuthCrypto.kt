package com.example.myapplication.multiplayer

import java.security.MessageDigest
import java.security.SecureRandom

internal abstract class JvmOnlineOAuthCrypto : OnlineOAuthPlatform {
    override fun randomBytes(size: Int): ByteArray = ByteArray(size).also(SecureRandom()::nextBytes)

    override suspend fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
