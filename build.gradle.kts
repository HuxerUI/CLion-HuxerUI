plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "org.huxerui"
version = "0.1.0"

val clionPath = providers.gradleProperty("clionPath")
val javaToolchainVersion = providers.gradleProperty("javaToolchainVersion").map(String::toInt).orElse(21)

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        if (!clionPath.isPresent) {
            clion("2025.3")
        } else {
            local(clionPath.get())
        }
        bundledPlugin("com.intellij.cmake")
        bundledPlugin("org.jetbrains.plugins.clion.radler")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        pluginVerifier()
    }
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = javaToolchainVersion.map(JavaLanguageVersion::of)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "org.huxerui"
        name = "HuxerUI"
        version = project.version.toString()
        description = """
            <p>Create, build, and run HuxerUI projects in CLion.</p>
            <ul>
                <li>Create App and Library projects with CMake or mcpp, and select a HuxerUI SDK for each project.</li>
                <li>Run and debug desktop apps with CLion. Select Web, Android, or iOS targets from the Run toolbar.</li>
                <li>Build and package apps, diagnose toolchains, and relaunch on source changes with Watch.</li>
                <li>Complete composable attributes and navigate from resource references to their source files.</li>
            </ul>
            <p>Requires CLion 2025.3 or later. Project creation and CMake workflows require HuxerUI SDK 0.5.0 or later.</p>
            <p><a href="https://github.com/HuxerUI/HuxerUI">HuxerUI documentation and SDK releases</a></p>
        """.trimIndent()
        changeNotes = """
            <p>Initial Marketplace release.</p>
            <ul>
                <li>Project creation and SDK management for HuxerUI App and Library projects.</li>
                <li>Run targets, Watch, build and packaging actions, and C++ editor support.</li>
            </ul>
        """.trimIndent()
        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
        vendor {
            name = "HuxerUI"
            url = "https://github.com/HuxerUI"
        }
    }
    pluginVerification {
        ides {
            if (clionPath.isPresent) {
                local(clionPath)
            } else {
                recommended()
            }
        }
    }
}

tasks {
    buildPlugin {
        from(layout.projectDirectory.file("LICENSE"))
    }
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 21
    }
    test {
        useJUnitPlatform()
        val testTemporaryDirectory = layout.buildDirectory.dir("tmp/test-jvm")
        doFirst {
            testTemporaryDirectory.get().asFile.mkdirs()
        }
        systemProperty("java.io.tmpdir", testTemporaryDirectory.get().asFile.absolutePath)
    }
}
