package com.example.coachserver.saves

import com.example.coachserver.FixedWindowRateLimiter
import com.example.coachserver.RequestRateLimiter
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.cloud.FirestoreClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

fun interface AccountVerifier {
    suspend fun verify(idToken: String): AccountVerification
}

sealed interface AccountVerification {
    data object Invalid : AccountVerification
    data object UnverifiedEmail : AccountVerification
    data class Verified(val uid: String, val authEpochSeconds: Long) : AccountVerification
}

class CloudSaveDependencies(
    val repository: CloudSaveRepository,
    val verifier: AccountVerifier,
    val deleteIdentity: suspend (uid: String) -> Unit,
    val historyEnabled: Boolean = true,
    val resumeEnabled: Boolean = false,
    val nowEpochSeconds: () -> Long = { Instant.now().epochSecond },
    val accountRateLimiter: RequestRateLimiter = FixedWindowRateLimiter(maxRequests = 120),
)

internal class FirebaseAccountVerifier(private val auth: FirebaseAuth) : AccountVerifier {
    override suspend fun verify(idToken: String): AccountVerification = withContext(Dispatchers.IO) {
        val token = try {
            auth.verifyIdToken(idToken, true)
        } catch (_: IllegalArgumentException) {
            return@withContext AccountVerification.Invalid
        } catch (error: FirebaseAuthException) {
            when (error.authErrorCode) {
                AuthErrorCode.INVALID_ID_TOKEN,
                AuthErrorCode.EXPIRED_ID_TOKEN,
                AuthErrorCode.REVOKED_ID_TOKEN,
                AuthErrorCode.USER_DISABLED,
                AuthErrorCode.USER_NOT_FOUND,
                AuthErrorCode.TENANT_ID_MISMATCH -> return@withContext AccountVerification.Invalid
                else -> throw error
            }
        }
        if (!token.isEmailVerified) return@withContext AccountVerification.UnverifiedEmail
        val authTime = (token.claims["auth_time"] as? Number)?.toLong()
        if (!isSaveId(token.uid) || authTime == null) AccountVerification.Invalid
        else AccountVerification.Verified(token.uid, authTime)
    }

    suspend fun delete(uid: String) = withContext(Dispatchers.IO) {
        try {
            auth.deleteUser(uid)
        } catch (error: FirebaseAuthException) {
            if (error.authErrorCode != AuthErrorCode.USER_NOT_FOUND) throw error
        }
    }
}

internal fun configuredCloudSaves(environment: Map<String, String>): CloudSaveDependencies? {
    val historyEnabled = environment["SAVES_HISTORY_ENABLED"] == "1"
    val resumeEnabled = environment["SAVES_RESUME_ENABLED"] == "1"
    if (!historyEnabled && !resumeEnabled) return null

    require("FIREBASE_AUTH_EMULATOR_HOST" !in environment && "FIRESTORE_EMULATOR_HOST" !in environment) {
        "Cloud saves require production Firebase endpoints"
    }
    val projectId = requireNotNull(environment["SAVES_FIREBASE_PROJECT_ID"]?.takeIf { it.isNotBlank() })
    val credential = requireNotNull(environment["SAVES_FIREBASE_SERVICE_ACCOUNT_JSON"]?.takeIf { it.isNotBlank() })
    val options = FirebaseOptions.builder()
        .setProjectId(projectId)
        .setCredentials(ServiceAccountCredentials.fromStream(credential.byteInputStream()))
        .setConnectTimeout(5_000)
        .setReadTimeout(10_000)
        .build()
    val app = FirebaseApp.initializeApp(options, "cloud-saves")
    try {
        val verifier = FirebaseAccountVerifier(FirebaseAuth.getInstance(app))
        return CloudSaveDependencies(
            repository = FirestoreCloudSaveRepository(FirestoreClient.getFirestore(app)),
            verifier = verifier,
            deleteIdentity = verifier::delete,
            historyEnabled = historyEnabled,
            resumeEnabled = resumeEnabled,
        )
    } catch (error: Exception) {
        app.delete()
        throw error
    }
}
