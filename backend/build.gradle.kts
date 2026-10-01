plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("io.ktor.plugin") version "3.5.2"
}

group = "com.example.nova"
version = "0.1.0"

application {
    mainClass.set("com.example.nova.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

ktor {
    fatJar {
        archiveFileName.set("nova-backend.jar")
    }
}

val ktorVersion = "3.5.2"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-rate-limit:$ktorVersion")
    implementation("io.ktor:ktor-server-forwarded-header:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")

    implementation("com.anthropic:anthropic-java:2.68.0")

    implementation("org.postgresql:postgresql:42.7.13")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.flywaydb:flyway-core:11.20.3")
    implementation("org.flywaydb:flyway-database-postgresql:11.20.3")

    implementation("com.nimbusds:nimbus-jose-jwt:10.10")
    implementation("org.commonmark:commonmark:0.30.0")
    implementation("org.eclipse.angus:angus-mail:2.0.5")
    implementation("ch.qos.logback:logback-classic:1.5.38")

    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation(kotlin("test"))
}

// Die Grundfassung der Texte aus cms/content wird ins JAR gepackt (Fallback, wenn Strapi nicht erreichbar ist).
tasks.processResources {
    from(rootDir.resolve("../cms/content")) {
        into("content")
    }
}

tasks.test {
    useJUnitPlatform()
    environment("DATABASE_URL", System.getenv("TEST_DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/nova_test")
    environment("DATABASE_USER", System.getenv("TEST_DATABASE_USER") ?: "nova")
    environment("DATABASE_PASSWORD", System.getenv("TEST_DATABASE_PASSWORD") ?: "nova")
}
