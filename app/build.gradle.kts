// Date of the last commit (YYYY-MM-DD): the same sources always give the same APK. Shallow CI clones still have HEAD.
val commitDate: String = runCatching {
    providers.exec {
        commandLine("git", "log", "-1", "--format=%cs")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.matches(Regex("""\d{4}-\d{2}-\d{2}""")) } ?: "unknown"

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("app.cash.paparazzi")
}

android {
    namespace = "by.carhome"
    compileSdk = 35
    defaultConfig {
        applicationId = "by.carhome"
        minSdk = 28
        targetSdk = 28
        // Semantic version: MAJOR.MINOR.PATCH; versionCode = MAJOR*10000 + MINOR*100 + PATCH.
        versionName = "1.1.0"
        versionCode = 10100
        // Shown in the about card (7 taps on the clock).
        buildConfigField("String", "BUILD_DATE", "\"$commitDate\"")
    }
    buildFeatures { buildConfig = true }
    // APK file name: bg-car-home-1.0.0-release.apk
    base.archivesName.set("bg-car-home-${defaultConfig.versionName}")
    // Release key from the environment (GitHub Actions secrets or a local shell). Without it the
    // release APK is signed with the debug key, which is fine for your own head unit.
    val keystore = System.getenv("CARHOME_KEYSTORE")
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("CARHOME_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("CARHOME_KEY_ALIAS")
            // PKCS12 (keytool's default format) has a single password for the store and the key.
            keyPassword = storePassword
        }
    }
    buildTypes {
        release {
            // Smaller dex/resources → less RAM and faster cold start on the head unit.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Head-unit app (Android 9), never published to Google Play.
    lint { disable += "ExpiredTargetSdkVersion" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
