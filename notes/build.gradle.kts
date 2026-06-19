plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.notes"
    }
}

dependencies {
    implementation(project(":library:ui"))
    implementRoom(libs)
    implementation(libs.reorderable)
    implementation("androidx.compose.material:material-icons-extended")
}