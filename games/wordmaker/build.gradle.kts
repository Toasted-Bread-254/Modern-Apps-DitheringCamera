plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

launcherIcon {
    symbol = "crossword"
    scale = 0.5
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.games.wordmaker"
    }
}

dependencies {
    implementation(libs.androidx.datastore.preferences)
}
