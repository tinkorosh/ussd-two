plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.example.ussdhelper"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  val vCode = (project.findProperty("versionCode") as? String)?.toIntOrNull()
      ?: System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
      ?: 1
  val vName = (project.findProperty("versionName") as? String)
      ?: System.getenv("VERSION_NAME")
      ?: if (vCode > 1) "1.0.$vCode" else "1.0"

  defaultConfig {
    applicationId = "com.example.ussdhelper"
    minSdk = 26
    targetSdk = 36
    versionCode = vCode
    versionName = vName

    // Size shrinking: retain only English resources to eliminate unused locales
    resourceConfigurations.addAll(listOf("en"))

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = true
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }

  packaging {
    resources {
      excludes += setOf(
        "META-INF/DEPENDENCIES",
        "META-INF/LICENSE",
        "META-INF/LICENSE.txt",
        "META-INF/license.txt",
        "META-INF/NOTICE",
        "META-INF/NOTICE.txt",
        "META-INF/notice.txt",
        "META-INF/ASL2.0",
        "META-INF/*.kotlin_module"
      )
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    viewBinding = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.material)
  implementation(libs.androidx.activity.ktx)
  implementation(libs.androidx.constraintlayout)
  implementation(libs.kotlinx.coroutines.android)

  testImplementation(libs.junit)
}
