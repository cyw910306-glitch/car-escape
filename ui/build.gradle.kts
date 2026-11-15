plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.paparazzi)
}
android {
    namespace = "com.carescape.ui"
    compileSdk = 35
    defaultConfig { minSdk = 28 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":core"))
    api(project(":game")) // 마이 차고 catalog (Look, Theme, unlock rules)
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)
    api(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
// Paparazzi 2.0.0-alpha04 breaks Gradle 8.14's HTML test report (missing HtmlWriterTools); JUnit XML still written.
tasks.withType<Test>().configureEach { reports.html.required.set(false) }
