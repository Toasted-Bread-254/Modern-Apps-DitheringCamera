plugins {
    id("common-conventions-library")
    alias(libs.plugins.ksp)
}

dependencies {
    // navigation 3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.compose.adaptive.navigation3)

    //reorderable
    implementation(libs.reorderable)

    // room
    implementRoom(libs)
    api(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)

    // datastore
    implementation(libs.androidx.datastore.preferences)

    // webkit
    implementation(libs.androidx.webkit)

    api(libs.material)
}