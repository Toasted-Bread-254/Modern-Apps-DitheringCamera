plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.clock"
    }
}
dependencies {
    implementRoom(libs)
}
