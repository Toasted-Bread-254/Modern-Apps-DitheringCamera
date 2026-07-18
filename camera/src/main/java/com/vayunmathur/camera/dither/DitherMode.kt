package com.vayunmathur.camera.dither

enum class DitherMode(val label: String) {
    NONE("None"),
    ORDERED_BAYER_2X2("Ordered Bayer 2x2"),
    ORDERED_BAYER_4X4("Ordered Bayer 4x4"),
    ORDERED_BAYER_8X8("Ordered Bayer 8x8"),
    FLOYD_STEINBERG("Floyd-Steinberg"),
    ATKINSON("Atkinson"),
    JARVIS_JUDICE_NINKE("Jarvis-Judice-Ninke"),
    STUCKI("Stucki"),
    BURKES("Burkes"),
    SIERRA("Sierra"),
    SIERRA_LITE("Sierra Lite")
}
