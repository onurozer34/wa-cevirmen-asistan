plugins {
  alias(libs.plugins.android.application)
}

android {
  namespace = "com.example.waevirmenasistan"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.waai.translator"
    minSdk = 24
    targetSdk = 36
    versionCode = 20
    versionName = "2.9"
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      signingConfig = signingConfigs.getByName("debug")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = false
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
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

dependencies {
  testImplementation(libs.junit)
  testImplementation("org.json:json:20240303")
}
