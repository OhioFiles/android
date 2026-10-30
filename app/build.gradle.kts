plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "cloud.ohiofiles.app"
  compileSdk = 36
  defaultConfig {
    applicationId = "cloud.ohiofiles.app"
    minSdk = 24
    targetSdk = 36
    versionCode = 5
    versionName = "3.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    manifestPlaceholders["appLabel"] = "OhioFiles"
  }

  testBuildType = if (providers.gradleProperty("qaIntegration").orNull == "true") "qa" else "debug"
  buildTypes {
    create("qa") {
      initWith(getByName("debug"))
      applicationIdSuffix = ".qa"
      versionNameSuffix = "-qa"
      matchingFallbacks += listOf("debug")
      manifestPlaceholders["appLabel"] = "OhioFiles QA"
    }
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      signingConfig = signingConfigs.getByName("debug")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    isCoreLibraryDesugaringEnabled = true
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  implementation("androidx.work:work-runtime-ktx:2.10.1")
  implementation("org.unifiedpush.android:connector:3.3.5")
  implementation("org.unifiedpush.android:embedded-fcm-distributor:3.1.0")

  implementation(platform("org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.10.2"))
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
  // Compose instrumentation loads the coroutine exception collector from the app classloader.
  debugImplementation(libs.kotlinx.coroutines.test)
  add("qaImplementation", libs.kotlinx.coroutines.test)
  androidTestImplementation(libs.kotlinx.coroutines.test)
  androidTestImplementation("androidx.work:work-testing:2.10.1")

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)

  implementation("com.caverock:androidsvg-aar:1.4")
  implementation("com.google.re2j:re2j:1.8")
  implementation("org.commonmark:commonmark:0.24.0")
  implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
  implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")
  implementation("com.tom-roush:pdfbox-android:2.0.27.0")
  implementation("com.google.android.filament:filament-android:1.64.1")
  implementation("com.google.android.filament:gltfio-android:1.64.1")
  implementation("com.google.android.filament:filament-utils-android:1.64.1")
  implementation("org.apache.commons:commons-compress:1.27.1")

  // Networking & Serialization
  implementation(libs.okhttp)
  implementation(libs.okhttp.logging)
  implementation(libs.kotlinx.serialization.json)

  // Image Loading
  implementation(libs.coil.compose)
  implementation("io.coil-kt:coil-svg:2.7.0")
  implementation("io.coil-kt:coil-gif:2.7.0")

  // DataStore
  implementation(libs.androidx.datastore.preferences)

  // QR Code
  implementation(libs.zxing.core)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  add("qaImplementation", libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}
