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
        .replace("\"", "\\\"") + "\""
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
        versionCode = 4
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "UPDATE_REPOSITORY_OWNER",
            buildConfigString(localPropertyOrEnv("HOMEATTACH_UPDATE_OWNER", githubOwner)),
        )
        buildConfigField(
            "String",
            "UPDATE_REPOSITORY_NAME",
            buildConfigString(localPropertyOrEnv("HOMEATTACH_UPDATE_REPO", githubRepo)),
        )
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
            isMinifyEnabled = false
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

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
