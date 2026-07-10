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
    compileSdk = 34

    defaultConfig {
        applicationId = "app.hanlu.furiganakeyboard"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // 18.1.0 is the latest line compatible with this project's Kotlin 1.9 toolchain.
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")

    // Local unit tests for the pure Kotlin logic (romaji / dictionary).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
