plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.web"
    }
}

dependencies {
    implementation(libs.geckoview)
    implementation(libs.jsoup)
    implementRoom(libs)
}
