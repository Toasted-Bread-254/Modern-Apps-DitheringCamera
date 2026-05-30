plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.weather"
    }
}

dependencies {
    implementation(project(":library:network"))
    implementation(libs.androidx.datastore.preferences)
    implementRoom(libs)
}
