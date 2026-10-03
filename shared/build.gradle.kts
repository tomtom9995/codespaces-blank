import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm()
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(layout.buildDirectory.dir("generated/content/kotlin"))
            dependencies {
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
                api(libs.coroutines.core)
                api(libs.serialization.json)
            }
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.coroutines.android)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.coroutines.test)
        }
    }
    compilerOptions {
        optIn.add("kotlin.io.encoding.ExperimentalEncodingApi")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

android {
    namespace = "com.example.nova.shared"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

/**
 * Baut die eingebaute Grundfassung der Texte aus cms/content/<sprache>/ in den Code ein.
 * So funktioniert die App beim ersten Start und offline – auch ohne erreichbares CMS.
 */
val generateBundledContent by tasks.registering {
    val contentDir = rootDir.resolve("cms/content")
    val outDir = layout.buildDirectory.dir("generated/content/kotlin/com/example/nova/shared/content")
    inputs.dir(contentDir)
    outputs.dir(outDir)
    doLast {
        val slurper = JsonSlurper()
        val locales = contentDir.listFiles()!!.filter { it.isDirectory }.map { it.name }.sorted()
        val entries = locales.joinToString(",\n") { locale ->
            fun read(name: String): Any = slurper.parse(contentDir.resolve("$locale/$name"))
            @Suppress("UNCHECKED_CAST")
            val settings = read("settings.json") as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val ui = (read("ui-texts.json") as List<Map<String, Any?>>).associate { it["key"] to it["text"] }.toSortedMap(compareBy { it.toString() })
            @Suppress("UNCHECKED_CAST")
            val onboarding = (read("onboarding-steps.json") as List<Map<String, Any?>>).map {
                mapOf(
                    "key" to it["key"], "order" to it["order"], "title" to it["title"], "body" to it["body"],
                    "primaryCta" to it["primaryCta"], "secondaryCta" to it["secondaryCta"], "tertiaryCta" to it["tertiaryCta"],
                    "skippable" to it["skippable"],
                )
            }.sortedBy { (it["order"] as Number).toInt() }
            @Suppress("UNCHECKED_CAST")
            val roles = (read("roles.json") as List<Map<String, Any?>>).map {
                mapOf("key" to it["key"], "scope" to it["scope"], "name" to it["name"], "description" to it["description"])
            }
            val bundle = mapOf(
                "version" to "bundled", "locale" to locale, "appName" to settings["appName"],
                "uiTexts" to ui, "onboarding" to onboarding, "roles" to roles,
            )
            val json = JsonOutput.toJson(bundle).replace("$", "\${'$'}")
            "    \"$locale\" to \"\"\"$json\"\"\""
        }
        val file = outDir.get().asFile.resolve("BundledContent.kt")
        file.parentFile.mkdirs()
        file.writeText(
            """
            |// Automatisch erzeugt aus cms/content – nicht von Hand bearbeiten.
            |package com.example.nova.shared.content
            |
            |internal val BUNDLED_CONTENT: Map<String, String> = mapOf(
            |$entries
            |)
            |""".trimMargin(),
        )
    }
}

tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach {
    dependsOn(generateBundledContent)
}
