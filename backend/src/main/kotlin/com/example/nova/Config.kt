package com.example.nova

/** Gesamte Konfiguration aus Umgebungsvariablen. In Produktion kommen Geheimnisse aus dem Secret Manager. */
data class Config(
    val port: Int,
    val publicBaseUrl: String,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val jwtSecret: String,
    val codePepper: String,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpUser: String?,
    val smtpPassword: String?,
    val smtpStartTls: Boolean,
    val mailFrom: String,
    val phoneProvider: String,
    val twilioAccountSid: String?,
    val twilioAuthToken: String?,
    val twilioFrom: String?,
    val strapiUrl: String?,
    val strapiToken: String?,
    val llmProvider: String,
    val anthropicModel: String,
    val openAiBaseUrl: String?,
    val openAiApiKey: String?,
    val openAiModels: List<String>,
    val googleClientIds: List<String>,
    val appleClientIds: List<String>,
    val trustGeoHeaders: Boolean,
    val onboardingEnabled: Boolean,
    val devMode: Boolean,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun get(name: String, default: String? = null): String =
                env[name]?.takeIf { it.isNotBlank() } ?: default ?: error("Umgebungsvariable $name fehlt")
            fun opt(name: String): String? = env[name]?.takeIf { it.isNotBlank() }
            fun list(name: String): List<String> =
                opt(name)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

            val devMode = get("DEV_MODE", "false").toBoolean()
            val llmProvider = opt("LLM_PROVIDER") ?: when {
                opt("ANTHROPIC_API_KEY") != null || opt("ANTHROPIC_AUTH_TOKEN") != null -> "anthropic"
                opt("OPENAI_BASE_URL") != null -> "openai"
                else -> "mock"
            }
            return Config(
                port = get("PORT", "8080").toInt(),
                publicBaseUrl = get("PUBLIC_BASE_URL", "http://localhost:8080").trimEnd('/'),
                databaseUrl = get("DATABASE_URL", "jdbc:postgresql://localhost:5432/nova"),
                databaseUser = get("DATABASE_USER", "nova"),
                databasePassword = get("DATABASE_PASSWORD", "nova"),
                jwtSecret = if (devMode) get("JWT_SECRET", "dev-only-secret-change-me-dev-only-secret") else get("JWT_SECRET"),
                codePepper = if (devMode) get("CODE_PEPPER", "dev-only-pepper") else get("CODE_PEPPER"),
                smtpHost = get("SMTP_HOST", "localhost"),
                smtpPort = get("SMTP_PORT", "1025").toInt(),
                smtpUser = opt("SMTP_USER"),
                smtpPassword = opt("SMTP_PASSWORD"),
                smtpStartTls = get("SMTP_STARTTLS", "false").toBoolean(),
                mailFrom = get("MAIL_FROM", "Nova <no-reply@example.com>"),
                phoneProvider = get("PHONE_PROVIDER", "dev"),
                twilioAccountSid = opt("TWILIO_ACCOUNT_SID"),
                twilioAuthToken = opt("TWILIO_AUTH_TOKEN"),
                twilioFrom = opt("TWILIO_FROM"),
                strapiUrl = opt("STRAPI_URL")?.trimEnd('/'),
                strapiToken = opt("STRAPI_TOKEN"),
                llmProvider = llmProvider,
                anthropicModel = get("ANTHROPIC_MODEL", "claude-opus-5-5"),
                openAiBaseUrl = opt("OPENAI_BASE_URL")?.trimEnd('/'),
                openAiApiKey = opt("OPENAI_API_KEY"),
                openAiModels = list("OPENAI_MODELS"),
                googleClientIds = list("GOOGLE_CLIENT_IDS"),
                appleClientIds = list("APPLE_CLIENT_IDS"),
                trustGeoHeaders = get("TRUST_GEO_HEADERS", "false").toBoolean(),
                onboardingEnabled = get("ONBOARDING_EMAILS", "true").toBoolean(),
                devMode = devMode,
            )
        }
    }
}
