plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.carescape.vehicle"
    compileSdk = 35
    defaultConfig { minSdk = 28 }
    useLibrary("android.car")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":core"))
    implementation(libs.pleos.vehicle)
}
