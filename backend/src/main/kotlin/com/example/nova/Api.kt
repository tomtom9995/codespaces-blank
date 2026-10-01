package com.example.nova

import kotlinx.serialization.Serializable

// Datenmodelle der öffentlichen API. Gegenstück in der App: shared/src/commonMain/.../api/Models.kt

@Serializable
data class ApiError(val error: String, val message: String)

@Serializable
data class DeviceInfo(
    /** Öffentlicher EC-P-256-Schlüssel des Geräts, X.509 SubjectPublicKeyInfo (DER), Base64. */
    val publicKey: String,
    val name: String,
    val platform: String,
    /** wifi | cellular | vpn | ethernet | unknown – vom Gerät gemeldet, nur schwaches Signal. */
    val connectionType: String = "unknown",
)

@Serializable
data class EmailStartRequest(val email: String, val marketingConsent: Boolean = false)

@Serializable
data class ChallengeResponse(val challengeId: String, val expiresInSeconds: Int, val target: String? = null)

@Serializable
data class EmailVerifyRequest(val challengeId: String, val code: String, val device: DeviceInfo)

@Serializable
data class IdTokenLoginRequest(val idToken: String, val device: DeviceInfo, val firstName: String? = null)

@Serializable
data class StepUpVerifyRequest(val challengeId: String, val code: String, val device: DeviceInfo)

@Serializable
data class LoginResponse(
    /** ok | step_up | blocked */
    val status: String,
    val tokens: Tokens? = null,
    val user: UserDto? = null,
    val isNewUser: Boolean = false,
    val stepUp: ChallengeResponse? = null,
    val message: String? = null,
)

@Serializable
data class Tokens(
    val accessToken: String,
    val accessTokenExpiresIn: Int,
    val refreshToken: String,
    val deviceId: String,
)

@Serializable
data class RefreshRequest(
    val refreshToken: String,
    val deviceId: String,
    /** Unix-Sekunden. */
    val timestamp: Long,
    /** ECDSA-SHA256-Signatur (DER, Base64) über "nova-refresh\n{refreshToken}\n{timestamp}". */
    val signature: String,
)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val firstName: String?,
    val useCase: String?,
    val phoneMasked: String?,
    val hasAntiPhishingPhrase: Boolean,
    val marketingConsent: Boolean,
    val workspace: WorkspaceDto,
)

@Serializable
data class WorkspaceDto(val id: String, val name: String, val role: String, val roleName: String)

@Serializable
data class UpdateMeRequest(val firstName: String? = null, val useCase: String? = null, val marketingConsent: Boolean? = null)

@Serializable
data class AntiPhishingRequest(val phrase: String)

@Serializable
data class PhoneStartRequest(val phone: String, val channel: String = "sms")

@Serializable
data class CodeRequest(val challengeId: String, val code: String)

@Serializable
data class DeviceDto(
    val id: String,
    val name: String,
    val platform: String,
    val lastSeenAt: String,
    val location: String?,
    val current: Boolean,
)

@Serializable
data class SecurityStatus(
    val hasPhone: Boolean,
    val hasAntiPhishingPhrase: Boolean,
    val activeDevices: Int,
    val recentEvents: List<SecurityEventDto>,
)

@Serializable
data class SecurityEventDto(val type: String, val at: String, val location: String?, val device: String?)

@Serializable
data class ModelDto(val id: String, val name: String, val provider: String)

@Serializable
data class ConversationDto(val id: String, val title: String, val model: String, val updatedAt: String)

@Serializable
data class MessageDto(val id: String, val role: String, val content: String, val createdAt: String)

@Serializable
data class ConversationDetailDto(val conversation: ConversationDto, val messages: List<MessageDto>)

@Serializable
data class CreateConversationRequest(val model: String? = null)

@Serializable
data class SendMessageRequest(val content: String)

/** Ereignisse im SSE-Strom einer Antwort. */
@Serializable
data class StreamEvent(
    /** start | delta | done | error */
    val type: String,
    val text: String? = null,
    val messageId: String? = null,
    val conversationTitle: String? = null,
    val message: String? = null,
)
