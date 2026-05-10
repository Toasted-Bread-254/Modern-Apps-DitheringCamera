plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.crypto"
    }
}

dependencies {
    // ktor
    implementation(project(":library:network"))

    // solana
    implementation(libs.sol4k)
}