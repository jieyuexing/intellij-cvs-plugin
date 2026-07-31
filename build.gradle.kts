// Maintainer: jieyuexing
// Fork of: https://github.com/JetBrains/intellij-obsolete-plugins/tree/master/cvs
// Primary target: IntelliJ IDEA 2026.2.1 (build 262.*)
// Secondary (later): IntelliJ IDEA 2023.2.8 (build 232.*)

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Preserve upstream multi-root layout (no mass source move).
sourceSets {
    main {
        java.setSrcDirs(
            listOf(
                "cvs-core/src",
                "cvs-plugin/src",
                "javacvs-src",
                "smartcvs-src",
            )
        )
        resources.setSrcDirs(
            listOf(
                "cvs-core/resources",
                "cvs-plugin/resources",
                "javacvs-src",
                "smartcvs-src",
            )
        )
    }
    test {
        java.setSrcDirs(listOf("testSource"))
    }
}

dependencies {
    // Upstream bundled SSH client (sources under trilead-ssh2-build213/ are reference-only)
    implementation(files("lib/trilead-ssh2-build213.jar"))

    intellijPlatform {
        // Nail primary: prefer local 2026.2.1 install; fall back to Maven coordinates.
        val localCandidates = listOf(
            file("${System.getProperty("user.home")}/Applications/IntelliJ IDEA.app"),
            file("/Applications/IntelliJ IDEA Ultimate.app"),
            file("/Applications/IntelliJ IDEA.app"),
        )
        val localIde = localCandidates.firstOrNull { candidate ->
            val info = candidate.resolve("Contents/Resources/product-info.json")
            info.isFile && info.readText().contains("2026.2")
        }
        if (localIde != null) {
            local(localIde)
        } else {
            val type = providers.gradleProperty("platformType")
            val ver = providers.gradleProperty("platformVersion")
            create(type, ver)
        }

        // Local IDE default CP only exposes vcs.core / vcs — not impl UI/vfs helpers.
        // plugin.xml depends on modules.vcs; compile needs impl for VcsVirtualFile, balloons, etc.
        bundledModule("intellij.platform.vcs.impl")
        bundledModule("intellij.platform.vcs.impl.lang")
        // DatePicker used by date/revision UI (bundled in IDE but not on default CP)
        bundledLibrary("lib/intellij.libraries.microba.jar")
    }
}

java {
    // IDEA 2026.2 ships bytecode 69 (Java 25); compile with the same major.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        id = "io.github.jieyuexing.cvs"
        name = "CVS (Community)"
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:none")
    }

    // First milestone: main sources for 2026.2.1. Upstream tests deferred.
    named<Test>("test") {
        enabled = false
    }
}
