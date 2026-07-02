plugins {
    id("common-conventions-library")
}

android {
    androidResources {
        // The PP-OCR .onnx models are already compressed; storing them
        // uncompressed keeps the APK asset directly readable.
        noCompress += "onnx"
    }
}

dependencies {
    // On-device OCR via ONNX Runtime (MIT, FOSS, no Play Services / no ML Kit).
    // This module owns the dependency; consumers only see the OcrEngine API.
    implementation(libs.onnxruntime.android)
}
