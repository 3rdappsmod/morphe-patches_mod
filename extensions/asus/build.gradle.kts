extension {
    name = "extensions/asus.mpe"
}

android {
    namespace = "org.ungoogled.asus"

    defaultConfig {
        // ASUS Gallery and File Manager require Android 14 (API 34).
        minSdk = 34
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
