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

// Target IDE platform — adjust when elevating compatibility.
intellij {
    version.set("2022.3")
}
