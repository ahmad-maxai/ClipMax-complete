import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.clipmax.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.clipmax.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "0.5.1"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"

        val gifProxyUrl = (System.getenv("CLIPMAX_GIF_PROXY_URL") ?: "")
            .trim()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val klipyApiKey = (System.getenv("KLIPY_API_KEY") ?: "")
            .trim()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "GIF_PROXY_URL", "\"$gifProxyUrl\"")
        buildConfigField("String", "KLIPY_API_KEY", "\"$klipyApiKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.testLogging {
                events("passed", "skipped", "failed")
            }
        }
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    testImplementation("junit:junit:4.13.2")
}

val forbiddenExtensions = listOf(".pdf", ".zip", ".apk")

fun findForbiddenAssetPaths(rootDir: File, label: String): List<String> {
    if (!rootDir.exists()) return emptyList()
    val violations = mutableListOf<String>()
    rootDir.walkTopDown().forEach { file ->
        val rel = file.relativeTo(rootDir).invariantSeparatorsPath
        if (rel.isEmpty()) return@forEach
        val lowerRel = rel.lowercase()
        if (lowerRel == "assets/aistudio" ||
            lowerRel.startsWith("assets/aistudio/") ||
            lowerRel.split("/").contains("aistudio")
        ) {
            violations.add("$label/$rel (AI Studio development evidence)")
        } else if (file.isFile && forbiddenExtensions.any { lowerRel.endsWith(it) }) {
            violations.add("$label/$rel (forbidden binary/archive/document)")
        }
    }
    return violations
}

fun verifyApkArchive(apkFile: File, label: String) {
    if (!apkFile.exists()) {
        throw GradleException("Expected $label APK not found at ${apkFile.absolutePath}")
    }
    val violations = mutableListOf<String>()
    var entryCount = 0
    ZipFile(apkFile).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            entryCount++
            val name = entry.name.replace('\\', '/')
            val lower = name.lowercase()
            if (lower.contains("assets/aistudio/") ||
                lower.split("/").contains("aistudio") ||
                forbiddenExtensions.any { lower.endsWith(it) }
            ) {
                violations.add(name)
            }
        }
    }
    if (violations.isNotEmpty()) {
        throw GradleException(
            "$label APK verification FAILED — development-only evidence found inside ${apkFile.name}:\n - " +
                violations.joinToString("\n - ")
        )
    }
    val digest = MessageDigest.getInstance("SHA-256")
    apkFile.inputStream().use { input ->
        val buf = ByteArray(8192)
        var read: Int
        while (input.read(buf).also { read = it } != -1) {
            digest.update(buf, 0, read)
        }
    }
    val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
    println("[✓] $label APK verified clean ($entryCount entries, ${apkFile.length()} bytes, SHA-256: $sha256): ${apkFile.absolutePath}")
}

val verifyOfflineWebAssets = tasks.register("verifyOfflineWebAssets") {
    group = "verification"
    description = "Fails the build if development-only files (.pdf, .zip, .apk, aistudio/) exist in web assets."
    doLast {
        val webAssetsDir = file("src/main/assets/web")
        if (!file("src/main/assets/web/index.html").isFile) {
            throw GradleException("Missing required offline web entry: ${webAssetsDir.absolutePath}/index.html")
        }
        val violations = mutableListOf<String>()
        violations += findForbiddenAssetPaths(webAssetsDir, "android/app/src/main/assets/web")
        val repoPublicDir = rootProject.projectDir.parentFile?.resolve("public")
        if (repoPublicDir != null && repoPublicDir.exists()) {
            violations += findForbiddenAssetPaths(repoPublicDir, "public")
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Build aborted — forbidden development evidence found in web assets:\n - " +
                    violations.joinToString("\n - ")
            )
        }
        println("[✓] Offline web assets verified clean (no .pdf, .zip, .apk, or aistudio/ evidence).")
    }
}

val verifyDebugApkAssets = tasks.register("verifyDebugApkAssets") {
    group = "verification"
    description = "Inspects the built debug APK archive to confirm no development evidence was packaged."
    doLast {
        verifyApkArchive(
            layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile,
            "Debug"
        )
    }
}

val verifyReleaseApkAssets = tasks.register("verifyReleaseApkAssets") {
    group = "verification"
    description = "Inspects the built release APK archive to confirm no development evidence was packaged."
    doLast {
        val unsignedRelease = layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk").get().asFile
        val signedRelease = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        val target = if (signedRelease.exists()) signedRelease else unsignedRelease
        verifyApkArchive(target, "Release")
    }
}

tasks.named("preBuild") {
    dependsOn(verifyOfflineWebAssets)
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(verifyReleaseApkAssets)
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(verifyDebugApkAssets, "testDebugUnitTest", "lintDebug", "assembleRelease")
}
