// Maintainer: jieyuexing
// Fork of: https://github.com/JetBrains/intellij-obsolete-plugins/tree/master/cvs
group = "io.github.jieyuexing"
version = "1.0.0-SNAPSHOT"

sourceSets {
    main {
        java.srcDirs(listOf("cvs-core/src", "cvs-plugin/src", "javacvs-src", "smartcvs-src"))
        resources.srcDirs(listOf("cvs-core/resources", "cvs-plugin/resources", "javacvs-src", "smartcvs-src"))
    }

    test {
        java.srcDirs("testSource")
    }
}

dependencies {
    implementation(files("lib/trilead-ssh2-build213.jar"))
}

// Maintainer Active targets (see README / AGENTS.md):
//   primary:   IntelliJ IDEA 2026.2.1  (~262.*)
//   secondary: IntelliJ IDEA 2023.2.8  (~232.*)
// Fragment below still reflects obsolete-plugins default; replace when Gradle is standalone.
intellij {
    version.set("2022.3")
}
