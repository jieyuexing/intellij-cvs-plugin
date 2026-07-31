// Maintainer: jieyuexing
// Fork of: https://github.com/JetBrains/intellij-obsolete-plugins/tree/master/cvs
//
// Install range: IU-232 … IU-262.* (both maintainer IDEs).
// Compile against local 2026.2 (APIs we adapted to) with Java 17 bytecode
// so IU-232 can load the class files (Java 25 bytecode would be rejected).

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
    implementation(files("lib/trilead-ssh2-build213.jar"))

    intellijPlatform {
        // Prefer primary local 2026.2 for compile classpath (source was adapted to it).
        val localCandidates = listOf(
            file("${System.getProperty("user.home")}/Applications/IntelliJ IDEA.app"),
            file("/Applications/IntelliJ IDEA.app"),
            file("/Applications/IntelliJ IDEA Ultimate.app"),
        )
        val local262 = localCandidates.firstOrNull { app ->
            val info = app.resolve("Contents/Resources/product-info.json")
            info.isFile && info.readText().contains("2026.2")
        }
        if (local262 != null) {
            local(local262)
        } else {
            create(
                providers.gradleProperty("platformType").orElse("IU"),
                providers.gradleProperty("platformVersion").orElse("2026.2.1"),
            )
        }

        bundledModule("intellij.platform.vcs.impl")
        bundledModule("intellij.platform.vcs.impl.lang")
        bundledLibrary("lib/intellij.libraries.microba.jar")
    }
}

java {
    // JDK 25 required to *read* 2026.2 platform class files (major 69).
    // Still emit release=17 so IDEA 2023.2 (232) can *load* our plugin classes.
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
            // Allow install on maintainer secondary IDE (232) through primary (262).
            sinceBuild = providers.gradleProperty("pluginSinceBuild").orElse("232")
            untilBuild = providers.gradleProperty("pluginUntilBuild").orElse("262.*")
        }
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(17)
        options.compilerArgs.add("-Xlint:none")
    }

    named<Test>("test") {
        enabled = false
    }
}
