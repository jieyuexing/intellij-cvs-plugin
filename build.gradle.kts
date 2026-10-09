import java.security.MessageDigest

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

        pluginVerifier()
        zipSigner()

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

    // 两代维护目标加中间版本；跨产品调查通过显式开关运行。
    pluginVerification {
        ides {
            local(file("${System.getProperty("user.home")}/Applications/IntelliJ IDEA.app"))
            create("IU", "2023.2.8")
            create("IU", "2024.3.6")
            if (providers.gradleProperty("verifyCrossProduct").orNull == "true") {
                create("WS", "2026.2")
            }
        }
    }

    pluginConfiguration {
        id = "io.github.jieyuexing.cvs"
        name = "OpenCVS"
        version = providers.gradleProperty("pluginVersion")
        changeNotes = """
            <p><b>262.0.3</b></p>
            <ul>
              <li>Prevent canceled or failed CVS content requests from caching an empty or partial diff baseline.</li>
              <li>Ignore and refresh zero-byte BaseRevisions caches left by earlier versions.</li>
              <li>取消或失败的 CVS 内容请求不再缓存空的或部分的 diff 基线。</li>
              <li>忽略并刷新早期版本留下的零字节 BaseRevisions 缓存。</li>
            </ul>
        """.trimIndent()

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

// 2.18.1 默认任务把 password 放入 Java argv；替换执行动作并保持官方文件接口。
intellijPlatform {
    signing {
        privateKeyFile = file("${System.getProperty("user.home")}/Library/Application Support/intellij-cvs-plugin/signing/private-key.pem")
        certificateChainFile = layout.projectDirectory.file("docs/signing-cert.pem")
        password = providers.environmentVariable("OPENCVS_SIGNING_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("ORG_GRADLE_PROJECT_intellijPlatformPublishingToken")
    }
}
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.SignPluginTask>("signPlugin") {
    actions.clear()
    outputs.upToDateWhen { false }
    doLast {
        val signer = zipSignerExecutable.get().asFile
        providers.exec {
            commandLine(
                "${System.getProperty("java.home")}/bin/java", "-cp", signer.absolutePath,
                file("scripts/SigningBridge.java").absolutePath, signer.absolutePath,
                archiveFile.get().asFile.absolutePath, signedArchiveFile.get().asFile.absolutePath,
                privateKeyFile.get().asFile.absolutePath, certificateChainFile.get().asFile.absolutePath,
            )
        }.result.get().assertNormalExitValue()
    }
}
// 发布只接受 prepare 锁定的同一个签名 ZIP；不重新构建或重新签名。
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.PublishPluginTask>("publishPlugin") {
    setDependsOn(emptyList<Any>())
    archiveFile = layout.buildDirectory.file("distributions/intellij-cvs-plugin-${project.version}.zip")
    doFirst {
        check(providers.environmentVariable("OPENCVS_MARKETPLACE_PREPARED_SHA256").isPresent) {
            "请通过 scripts/release.py marketplace 使用已核验回执"
        }
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(archiveFile.get().asFile.readBytes()).joinToString("") { "%02x".format(it) }
        check(actual == providers.environmentVariable("OPENCVS_MARKETPLACE_PREPARED_SHA256").get()) {
            "签名 ZIP 已变化；拒绝上传"
        }
    }
}

// 单独核验最终同名发布资产，不触发重签。
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginSignatureTask>("verifyPluginSignature") {
    inputArchiveFile = layout.buildDirectory.file("distributions/intellij-cvs-plugin-${project.version}.zip")
    certificateChainFile = layout.projectDirectory.file("docs/signing-cert.pem")
}
