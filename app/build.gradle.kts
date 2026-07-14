import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}

android {
    namespace = "com.example.furiganakeyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.hanlu.furiganakeyboard"
        minSdk = 24
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.0-rc.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions")
            }
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
        }
        create("direct") {
            dimension = "distribution"
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.isFile) {
            create("release") {
                fun requiredProperty(name: String): String =
                    requireNotNull(keystoreProperties.getProperty(name)) {
                        "Missing $name in ${keystorePropertiesFile.path}"
                    }

                // Resolve storeFile relative to the real properties file. This also
                // supports a local key.properties symlink to another project.
                val propertiesDirectory = keystorePropertiesFile.canonicalFile.parentFile
                storeFile = propertiesDirectory.resolve(requiredProperty("storeFile")).canonicalFile
                storePassword = requiredProperty("storePassword")
                keyAlias = requiredProperty("keyAlias")
                keyPassword = requiredProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = false
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    bundle {
        language {
            enableSplit = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Keep Play's self-update code out of the directly distributed APK, and
    // keep the external APK updater out of the Play artifact.
    "playImplementation"("com.google.android.play:app-update:2.1.0")
    implementation("com.google.mlkit:digital-ink-recognition:19.0.0")

    // Local unit tests for the pure Kotlin logic (romaji / dictionary).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

// A release artifact without the configured upload/signing key must never be
// mistaken for something that can be published.
tasks.matching {
    it.name == "prePlayReleaseBuild" || it.name == "preDirectReleaseBuild"
}.configureEach {
    doFirst {
        check(keystorePropertiesFile.isFile) {
            "Release builds require key.properties; see RELEASING.md"
        }
    }
}
