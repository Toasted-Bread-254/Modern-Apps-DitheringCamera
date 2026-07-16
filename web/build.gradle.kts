plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "public"
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
