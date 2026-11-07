extension {
    name = "extensions/xiaomiremote.mpe"
}

android {
    namespace = "org.ungoogled.xiaomiremote"

    defaultConfig {
        // Mi Remote itself requires Android 5.1 (API 22); the extension only needs what its own code uses.
        minSdk = 29
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
