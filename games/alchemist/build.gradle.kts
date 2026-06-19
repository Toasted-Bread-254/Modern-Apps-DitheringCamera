plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.games.alchemist"
    }
}

dependencies {
    implementation(project(":library:ui"))
}