import com.android.build.gradle.internal.tasks.L8DexDesugarLibTask
import java.security.KeyStore
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
}

// Optional local-only input. CI and ordinary source builds contain no accessory identity.
val localAuthenticationAssets = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR")
    .orNull?.let { file(it).canonicalFile }
    ?: rootProject.file(".private/runtime-assets").takeIf { it.isDirectory }?.canonicalFile
    ?: rootProject.file("auth-assets").takeIf { it.isDirectory }?.canonicalFile

android {
    namespace = "com.shilapi.xcertplay"
    testBuildType = providers.gradleProperty("legacyInstrumentationBuildType").getOrElse("debug")
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.shihab.diplay"
        minSdk = 19
        targetSdk = 37
        multiDexEnabled = true
        multiDexKeepProguard = file("multidex-config.pro")
        testInstrumentationRunner = "com.shilapi.xcertplay.T3LegacyInstrumentation"
        versionCode = 52
        versionName = "0.2.32"

    }


    localAuthenticationAssets?.let { sourceSets.getByName("main").assets.srcDir(it) }

    signingConfigs {
        create("release") {
            val localKeystore = rootProject.file("release-signing.jks").takeIf { it.isFile }
                ?: rootProject.file("release-signing.keystore").takeIf { it.isFile }
            storeFile = providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                .orNull?.let { file(it) } ?: localKeystore ?: file("missing-release-keystore.jks")
            val hasLocal = localKeystore != null
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orElse(if (hasLocal) "diplay123456" else "").get()
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orElse(if (hasLocal) "diplay" else "").get()
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orElse(if (hasLocal) "diplay123456" else "").get()
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".hudtest"
            versionNameSuffix = "-hud-test"
        }
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
}

val authenticationProbeRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// AGP 9.3 shrinks test L8 separately, shadowing target-app methods on native multidex Android.
// Keep a complete compatibility library in the test APK only; production remains unchanged.
afterEvaluate {
    tasks.withType<L8DexDesugarLibTask>().configureEach {
        if (name.endsWith("AndroidTest")) {
            keepRulesConfigurations.add("-keep class j$.** { *; }")
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":common"))
    implementation(project(":shared"))
    implementation("androidx.multidex:multidex:2.0.1")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    authenticationProbeRuntime(libs.bouncycastle)
    authenticationProbeRuntime("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
}

abstract class VerifyDiPlaySigningTask : DefaultTask() {
    @get:org.gradle.api.tasks.Internal abstract val keyStoreFile: RegularFileProperty
    @get:Input abstract val keyStorePassword: Property<String>
    @get:Input abstract val keyAlias: Property<String>
    @get:Input abstract val keyPassword: Property<String>

    @TaskAction fun verify() {
        val keyFile = keyStoreFile.orNull?.asFile
        check(keyFile?.isFile == true && keyStorePassword.get().isNotBlank() &&
            keyAlias.get().isNotBlank() && keyPassword.get().isNotBlank()) {
            "Release requires the original DiPlay signing keystore and explicit ANDROID_KEY* variables. Debug signing is forbidden."
        }
        val secret = keyStorePassword.get().toCharArray()
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        try {
            keyFile!!.inputStream().use { store.load(it, secret) }
        } catch (error: Exception) {
            throw GradleException("Release signing keystore cannot be opened", error)
        } finally {
            secret.fill(0.toChar())
        }
        val alias = keyAlias.get()
        check(store.isKeyEntry(alias)) { "Release signing alias is not a private key entry" }
        val privateKey = runCatching { store.getKey(alias, keyPassword.get().toCharArray()) }.getOrNull()
        check(privateKey is java.security.PrivateKey) { "Release signing private key cannot be unlocked with ANDROID_KEY_PASSWORD" }
        val cert = store.getCertificate(alias) ?: throw GradleException("Release signing certificate not found")
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(fingerprint.equals("bca015c0cb43469fee55539b4054ff862671e7b41e6612eee0cb8566d327b7ae", true)) {
            "Release certificate differs from v0.2.21; refusing to create an update-incompatible APK."
        }
    }
}
val verifyReleaseSigningIdentity by tasks.registering(VerifyDiPlaySigningTask::class) {
    group = "verification"
    description = "Validate the original DiPlay release certificate before any release APK is signed."
    keyStoreFile.set(layout.file(providers.provider { android.signingConfigs.getByName("release").storeFile!! }))
    val localKeystore = rootProject.file("release-signing.jks").takeIf { it.isFile }
        ?: rootProject.file("release-signing.keystore").takeIf { it.isFile }
    val hasLocal = localKeystore != null
    keyStorePassword.set(providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orElse(if (hasLocal) "diplay123456" else ""))
    keyAlias.set(providers.environmentVariable("ANDROID_KEY_ALIAS").orElse(if (hasLocal) "diplay" else ""))
    keyPassword.set(providers.environmentVariable("ANDROID_KEY_PASSWORD").orElse(if (hasLocal) "diplay123456" else ""))
}
tasks.matching { it.name == "validateSigningRelease" }.configureEach {
    dependsOn(verifyReleaseSigningIdentity)
}
// No implicit import. Only the two explicitly selected local runtime assets are allowed.
val credentialAssets = files(android.sourceSets.flatMap { source ->
    source.assets.directories.map { directory ->
        fileTree(directory) {
            include("**/offline-mfi/**", "**/*.pk8", "**/*.p7b", "**/*.key",
                "**/*.pem", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore")
        }
    }
})
val rejectBundledCredentials by tasks.registering {
    group = "verification"
    description = "Reject unexpected credential files in APK assets."
    val filesToCheck = credentialAssets
    val allowed = localAuthenticationAssets?.let { dir ->
        listOf("identity.pk8", "certificate.p7b").map { dir.resolve("offline-mfi/$it").canonicalFile }.toSet()
    } ?: emptySet()
    inputs.files(filesToCheck)
    doLast {
        check(allowed.all { it.isFile }) { "Explicit local authentication assets are incomplete" }
        val unexpected = filesToCheck.files.filter { it.canonicalFile !in allowed }
        check(unexpected.isEmpty()) { "Unexpected credential files in APK assets" }
    }
}
tasks.named("preBuild") { dependsOn(rejectBundledCredentials) }

// Car-test packages must be standalone. Keep ordinary source/CI builds identity-free.
val requireStandaloneAuthentication by tasks.registering {
    group = "verification"
    description = "Require the explicit runtime authentication input for a standalone car-test APK."
    val directory = localAuthenticationAssets
    doLast {
        check(directory != null) {
            "Standalone car builds require DIPLAY_AUTH_ASSETS_DIR; assembleDebug alone is source-only."
        }
        check(listOf("identity.pk8", "certificate.p7b").all {
            directory.resolve("offline-mfi/$it").let { file -> file.isFile && file.length() > 0 }
        }) { "Standalone CarPlay authentication files are missing or empty" }
    }
}
val verifyStandaloneAuthentication by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Validate standalone key/certificate matching and challenge signatures before packaging."
    dependsOn(requireStandaloneAuthentication, ":shared:bundleLibRuntimeToJarDebug")
    classpath(authenticationProbeRuntime,
        project(":shared").layout.buildDirectory.file(
            "intermediates/runtime_library_classes_jar/debug/bundleLibRuntimeToJarDebug/classes.jar"))
    mainClass.set("com.shilapi.xcertplay.mfi.LocalMfiProbe")
    localAuthenticationAssets?.let { args(it.resolve("offline-mfi").absolutePath) }
}
tasks.named("preBuild") { mustRunAfter(verifyStandaloneAuthentication) }
tasks.register("assembleStandaloneDebug") {
    group = "build"
    description = "Build a standalone car-test APK with explicitly provisioned authentication."
    dependsOn(verifyStandaloneAuthentication, "assembleDebug")
}

tasks.register("assembleStandaloneRelease") {
    group = "build"
    description = "Build a signed standalone APK with explicitly provisioned authentication."
    dependsOn(verifyStandaloneAuthentication, verifyReleaseSigningIdentity, "assembleRelease")
}
