import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.isFile) {
        localPropertiesFile.inputStream().use { inputStream ->
            load(inputStream)
        }
    }
}

val dotenvProperties = rootProject.file(".env")
    .takeIf { it.isFile }
    ?.readLines()
    ?.asSequence()
    ?.map(String::trim)
    ?.filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
    ?.associate { line ->
        val key = line.substringBefore("=").trim()
        val rawValue = line.substringAfter("=").trim()
        val value = rawValue
            .removeSurrounding("\"")
            .removeSurrounding("'")
        key to value
    }
    .orEmpty()

fun localPropertyOrEnv(name: String, defaultValue: String = ""): String {
    return localProperties.getProperty(name)
        ?: dotenvProperties[name]
        ?: providers.environmentVariable(name).orNull
        ?: defaultValue
}

fun buildConfigString(value: String): String {
    return "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r") + "\""
}

val githubRepository = providers.environmentVariable("GITHUB_REPOSITORY").orNull.orEmpty()
val githubOwner = githubRepository.substringBefore("/", "")
val githubRepo = githubRepository.substringAfter("/", "")

android {
    namespace = "com.homeattach.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.homeattach.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.0.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The self-updater reads a static version manifest from a CDN direct-download URL, never the
        // GitHub REST API (60/h anonymous rate limit + draft/prerelease 404s broke the channel).
        // Default is derived from the owner/repo so zero-config builds still work; override with
        // HOMEATTACH_UPDATE_MANIFEST_URL if the manifest is hosted elsewhere.
        val updateOwner = localPropertyOrEnv("HOMEATTACH_UPDATE_OWNER", githubOwner)
        val updateRepo = localPropertyOrEnv("HOMEATTACH_UPDATE_REPO", githubRepo)
        val manifestDefault = if (updateOwner.isNotBlank() && updateRepo.isNotBlank()) {
            "https://github.com/$updateOwner/$updateRepo/releases/latest/download/update.json"
        } else {
            ""
        }
        buildConfigField(
            "String",
            "UPDATE_MANIFEST_URL",
            buildConfigString(localPropertyOrEnv("HOMEATTACH_UPDATE_MANIFEST_URL", manifestDefault)),
        )

        val debugHost = localPropertyOrEnv("HOMEATTACH_HOME_HOST", "")
        val debugPort = localPropertyOrEnv("HOMEATTACH_HOME_PORT", "22").toIntOrNull() ?: 22
        val debugUsername = localPropertyOrEnv("HOMEATTACH_HOME_USERNAME", "")
        val debugKeyPath = localPropertyOrEnv("HOMEATTACH_HOME_PRIVATE_KEY_FILE", "")
        val debugKeyContent = if (debugKeyPath.isNotBlank()) {
            val f = file(debugKeyPath)
            if (f.exists()) f.readText() else ""
        } else {
            ""
        }

        buildConfigField("String", "HOMEATTACH_DEBUG_HOST", buildConfigString(debugHost))
        buildConfigField("int", "HOMEATTACH_DEBUG_PORT", debugPort.toString())
        buildConfigField("String", "HOMEATTACH_DEBUG_USERNAME", buildConfigString(debugUsername))
        buildConfigField("String", "HOMEATTACH_DEBUG_PRIVATE_KEY", buildConfigString(debugKeyContent))
    }

    signingConfigs {
        create("release") {
            storeFile = file(localPropertyOrEnv("HOMEATTACH_RELEASE_STORE_FILE", "missing-release-keystore.jks"))
            storePassword = localPropertyOrEnv("HOMEATTACH_RELEASE_STORE_PASSWORD")
            keyAlias = localPropertyOrEnv("HOMEATTACH_RELEASE_KEY_ALIAS")
            keyPassword = localPropertyOrEnv("HOMEATTACH_RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // R8 full mode: prunes the app's dead code, unused Compose/androidx, and the parts of
            // BouncyCastle we never call. proguard-rules.pro keeps the reflection-resolved crypto
            // that R8 cannot see (JSch/BC ed25519), verified by a real ed25519 connect on device.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    androidResources {
        // Keep the bundled CJK font uncompressed so it can be memory-mapped by createFromAsset.
        noCompress += "ttf"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.graphics.path)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.jsch)
    // JSch's ed25519 signing needs either JDK15+'s built-in "Ed25519" JCA algorithm (not present
    // on the Android runtime) or BouncyCastle's implementation - see SshClient.ensureEd25519Support().
    implementation(libs.bouncycastle)
    implementation(libs.androidx.security.crypto)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.compose.material:material-icons-extended")
    // Full-config QR payloads (tsess-qr-config) are YAML; JSON payloads parse as its subset.
    implementation("org.yaml:snakeyaml:2.2")

    testImplementation(libs.junit)
    // Android's bundled org.json is a stub on the JVM unit-test classpath; the real impl lets
    // parseManifest be exercised in plain unit tests. Does not affect the app APK.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
