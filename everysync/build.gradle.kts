plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.everysync"

        // OAuth2 public-client IDs (PKCE, no secret shipped). Registered clients
        // must use the redirect scheme "com.vayunmathur.everysync:/oauth". Empty
        // placeholders let the module compile; provide real IDs to test Google /
        // Withings integrations.
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"${project.findProperty("EVERYSYNC_GOOGLE_CLIENT_ID") ?: ""}\"")
        buildConfigField("String", "WITHINGS_OAUTH_CLIENT_ID", "\"${project.findProperty("EVERYSYNC_WITHINGS_CLIENT_ID") ?: ""}\"")
        buildConfigField("String", "WITHINGS_OAUTH_CLIENT_SECRET", "\"${project.findProperty("EVERYSYNC_WITHINGS_CLIENT_SECRET") ?: ""}\"")
    }
    buildFeatures {
        buildConfig = true
    }
}

metadataScreenshots {
    permissions.addAll(
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.READ_CALENDAR",
        "android.permission.WRITE_CALENDAR",
        "android.permission.GET_ACCOUNTS",
    )
}

dependencies {
    // Custom Tabs for OAuth PKCE flow
    implementation(libs.androidx.browser)

    // Health Connect (Withings + Samsung/Google Health bridge)
    implementation(libs.androidx.connect.client)

    // Background sync
    implementation(libs.androidx.work.runtime.ktx)

    // ktor client (custom WebDAV methods + JSON)
    implementation(project(":library:network"))

    // Material icons (Default.* + AutoMirrored)
    implementation("androidx.compose.material:material-icons-extended")

    testImplementation(libs.junit)
}
