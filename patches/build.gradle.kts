group = "org.ungoogled"

patches {
    about {
        name = "Danpoong patches"
        description = "Samsung Music on non-One UI ROMs, OnePlus Documents on non-ColorOS ROMs, Sony Music on non-Xperia ROMs, ASUS Gallery and File Manager on non-ASUS ROMs, and Mi Remote on non-HyperOS ROMs."
        source = "https://github.com/3rdappsmod/morphe-patches_mod.git"
        author = "3rdappsmod"
        contact = "https://github.com/3rdappsmod"
        website = "https://github.com/3rdappsmod/morphe-patches_mod"
        license = "GPLv3"
    }
}

// Separate configuration so gson is available at runtime for the
// generatePatchesList task but never bundled into the APK.
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")

dependencies {
    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
    }

    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}
