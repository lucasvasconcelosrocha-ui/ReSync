import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("kotlin-kapt")
}

android {
    namespace = "com.resync.app"
    compileSdk = 35

    val versionPropertiesFile = rootProject.file("version.properties")
    val versionProperties = Properties()
    if (versionPropertiesFile.exists()) {
        versionPropertiesFile.inputStream().use { versionProperties.load(it) }
    }
    val vCode = (System.getenv("VERSION_CODE")
        ?: project.findProperty("versionCode")?.toString()
        ?: versionProperties.getProperty("versionCode")
        ?: "3").toInt()
    val vName = System.getenv("VERSION_NAME")
        ?: project.findProperty("versionName")?.toString()
        ?: versionProperties.getProperty("versionName")
        ?: "1.0.2"

    defaultConfig {
        applicationId = "com.resync.app"
        minSdk = 26
        targetSdk = 35
        versionCode = vCode
        versionName = vName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            val keystoreProps = Properties()
            if (keystorePropertiesFile.exists()) {
                keystorePropertiesFile.inputStream().use { keystoreProps.load(it) }
            }

            val keystorePath = System.getenv("KEYSTORE_FILE")
                ?: System.getenv("KEYSTORE_PATH")
                ?: keystoreProps.getProperty("storeFile")
                ?: "resync-release.jks"
            val keystorePass = System.getenv("KEYSTORE_PASSWORD")
                ?: keystoreProps.getProperty("storePassword")
            val alias = System.getenv("KEY_ALIAS")
                ?: keystoreProps.getProperty("keyAlias")
                ?: "resync"
            val keyPass = System.getenv("KEY_PASSWORD")
                ?: keystoreProps.getProperty("keyPassword")

            val resolvedKeystoreFile = run {
                val f = File(keystorePath)
                if (f.isAbsolute && f.exists()) f
                else if (rootProject.file(keystorePath).exists()) rootProject.file(keystorePath)
                else if (file(keystorePath).exists()) file(keystorePath)
                else null
            }

            if (resolvedKeystoreFile != null && resolvedKeystoreFile.exists() &&
                !keystorePass.isNullOrBlank() && !keyPass.isNullOrBlank()) {
                storeFile = resolvedKeystoreFile
                storePassword = keystorePass
                keyAlias = alias
                keyPassword = keyPass
            } else {
                throw GradleException("Credenciais de assinatura de release ausentes ou incompletas! Defina KEYSTORE_PASSWORD e KEY_PASSWORD via variáveis de ambiente/GitHub Secrets ou no arquivo keystore.properties.")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "win/**",
                "win32/**",
                "win64/**",
                "darwin/**",
                "osx/**",
                "net/jpountz/util/darwin/**",
                "net/jpountz/util/win32/**",
                "net/jpountz/util/linux/amd64/**",
                "net/jpountz/util/linux/i386/**",
                "**/*.dll",
                "**/*.dylib"
            )
        }
        jniLibs {
            useLegacyPackaging = false
            excludes += listOf(
                "**/win/**",
                "**/win32/**",
                "**/win64/**",
                "**/darwin/**",
                "**/osx/**",
                "**/linux/amd64/**",
                "**/linux/x86_64/**",
                "**/linux/i386/**",
                "**/*.dll",
                "**/*.dylib"
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { testTask ->
            val tmpDir = file("${project.layout.buildDirectory.get().asFile}/tmp")
            tmpDir.mkdirs()
            testTask.systemProperty("java.io.tmpdir", tmpDir.absolutePath)
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // Google API Client & Drive API
    implementation("com.google.api-client:google-api-client-android:2.6.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20220815-2.0.0")
    implementation("com.google.http-client:google-http-client-gson:1.44.1")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("com.google.android.gms:play-services-base:18.5.0")
    // AndroidX Core & Lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.8.3")

    // Jetpack DataStore Preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Jetpack Compose BOM & UI
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // WorkManager for background and scheduled backups
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // JSON serialization
    implementation("com.google.code.gson:gson:2.11.0")

    // HTTP client for update checking and downloads
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // LibSU for elevated root capability checking & legitimate execution
    implementation("com.github.topjohnwu.libsu:core:5.2.2")
    implementation("com.github.topjohnwu.libsu:io:5.2.2")

    // Apache Commons Compress for Zip64, STORED streaming, multi-part, and Tar POSIX handling
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("com.github.luben:zstd-jni:1.5.6-3")
    implementation("org.lz4:lz4-java:1.8.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Task de geração automática de ícones com fundo 100% transparente
tasks.register<Exec>("generateAppIcons") {
    group = "build"
    description = "Gera ícones adaptativos e legados com fundo 100% transparente e logo centralizada a 55%"
    workingDir = rootDir
    commandLine("python3", "scripts/generate_icons.py")
}

