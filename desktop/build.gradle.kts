import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose.compiler)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
        )
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.components.resources)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    // Talk to the Python ML sidecar over loopback HTTP
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // Wi-Fi sync receiver: HTTP server + mDNS advertisement (phone/watch uploads)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.jmdns)

    implementation(libs.slf4j.simple)
}

compose.desktop {
    application {
        mainClass = "com.transcribbio.desktop.MainKt"

        // jlink/jpackage need a full JDK (with jmods). Use the portable one in .tooling
        // when present (the machine's default JDK may be a trimmed runtime without jmods).
        val fullJdk = rootProject.file(".tooling/jdk17-full")
        if (fullJdk.resolve("bin/jlink.exe").exists()) {
            javaHome = fullJdk.absolutePath
        }

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Transcribbio"
            packageVersion = "1.0.0"
            description = "Personal Slovak lecture transcription & study-material generator"
            vendor = "Transcribbio"

            windows {
                menuGroup = "Transcribbio"
                shortcut = true
                // Stable UUID so upgrades replace rather than duplicate the install.
                upgradeUuid = "0f9d2c1e-6a4b-4b2a-9d3e-2f5a1b7c8d90"
            }

            // The ML sidecar (Python) ships alongside the app under app resources.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("appResources"))
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}
