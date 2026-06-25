plugins {
    id("common-conventions-library")
}

dependencies {
    implementation(project(":library"))
    implementation("androidx.compose.material:material-icons-extended")
}
