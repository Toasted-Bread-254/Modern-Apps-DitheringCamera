plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.clock"
    }
}
dependencies {
    implementation(project(":library:ui"))
    implementRoom(libs)
}
