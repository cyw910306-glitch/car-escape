import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.carescape.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.carescape.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    // release 서명: signing/keystore.properties 가 있을 때만. 없으면 서명 없는 release APK 를 만든다.
    // v3 는 키 로테이션 대비로 명시적으로 켠다. minSdk 28 이라 apksig 가 v1·v2 블록은 생략한다(v3 로 충분). 상세: signing/README.md
    val keystoreProps = rootProject.file("signing/keystore.properties")
    val releaseSigning = if (keystoreProps.exists()) {
        val p = Properties().apply { keystoreProps.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = keystoreProps.parentFile.resolve(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
            enableV3Signing = true
        }
    } else null
    useLibrary("android.car")
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        // DEMO: start on SimVehicleGateway with the demo panel. Release still falls back to Sim if Pleos never answers.
        debug { buildConfigField("boolean", "DEMO", "true") }
        release {
            buildConfigField("boolean", "DEMO", "false")
            signingConfig = releaseSigning
        }
        // car: real PleosVehicleGateway, debug-signed, for the Pleos Connect Emulator + Virtual Console.
        create("car") {
            initWith(getByName("debug"))
            buildConfigField("boolean", "DEMO", "false")
            matchingFallbacks += "debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":core"))
    implementation(project(":game"))
    implementation(project(":vehicle"))
    implementation(project(":ui"))
    implementation(libs.activity.compose)
}
