plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-kapt")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.0"
}

android {
    namespace = "com.domain.app"
    compileSdk = 34

    defaultConfig {
        // Anthemos 2.0 installs beside 1.x, so it never touches 1.x data.
        applicationId = "com.anthemos.app.two"
        minSdk = 24
        targetSdk = 34
        // CI numbers each build so the in-app updater can tell newer from older.
        val build = System.getenv("ANTHEMOS_BUILD")?.toInt() ?: 3
        versionCode = build
        versionName = "2.0.0-alpha12 (build $build)"

        // Phones only: keeps the speech engine's native code to one copy.
        ndk { abiFilters += listOf("arm64-v8a") }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // One fixed key (a CI secret), so each build installs over the last.
    // Without it, local builds use the usual per-machine debug key.
    val keystore = System.getenv("ANTHEMOS_KEYSTORE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("anthemos") {
                storeFile = keystore
                storePassword = System.getenv("ANTHEMOS_KEYSTORE_PASSWORD")
                keyAlias = "anthemos"
                keyPassword = System.getenv("ANTHEMOS_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (keystore != null) signingConfig = signingConfigs.getByName("anthemos")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "11"
        // FIX: Add opt-in for experimental Material3 APIs
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi"
        )
    }
    buildFeatures {
        compose = true
	buildConfig = true
    }
    lint {
        disable += "SuspiciousModifierThen"
    }
}

dependencies {
    // Core library desugaring
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    
    // Compose
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.foundation:foundation:1.5.4")
    
    // Material Icons Extended
    implementation("androidx.compose.material:material-icons-extended:1.5.4")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    
    // SQLCipher for database encryption
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")
    
    // Security & Encryption
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    
    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")
    implementation("androidx.navigation:navigation-compose:2.7.6")
    
    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    
    // Preferences
    implementation("androidx.preference:preference-ktx:1.2.1")
    
    // Dependency Injection
    implementation("com.google.dagger:hilt-android:2.48")
    kapt("com.google.dagger:hilt-compiler:2.48")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    
    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // WorkManager for scheduled backups
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    // Hilt integration with WorkManager
    implementation("androidx.hilt:hilt-work:1.1.0")
    kapt("androidx.hilt:hilt-compiler:1.1.0")
    
    // Serialization for backup data (if not already included)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    
    // DocumentFile for file operations (if not already included)
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation(libs.androidx.material3)

    // Networking for Direct chat
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // On-device speech to text (voice/Listener.kt)
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
}

// The speech model ships inside the APK but not in git: fetched on the first
// build into src/main/assets/model-en-us (gitignored).
val voskModel = "vosk-model-small-en-us-0.15"
val fetchVoskModel by tasks.registering {
    val out = file("src/main/assets/model-en-us")
    val zip = layout.buildDirectory.file("$voskModel.zip").get().asFile
    onlyIf { !File(out, "am/final.mdl").exists() }
    doLast {
        zip.parentFile.mkdirs()
        uri("https://alphacephei.com/vosk/models/$voskModel.zip").toURL().openStream().use { input ->
            zip.outputStream().use { input.copyTo(it) }
        }
        out.deleteRecursively()
        copy {
            from(zipTree(zip))
            into(out)
            includeEmptyDirs = false
            // Drop the zip's top folder.
            eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
        }
    }
}
tasks.named("preBuild") { dependsOn(fetchVoskModel) }

kapt {
    correctErrorTypes = true
}
