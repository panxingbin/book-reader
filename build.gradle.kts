plugins {
    kotlin("jvm") version "1.9.24"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.bookreader"
// Single-sourced from gradle.properties. Bump `pluginVersion` there before
// packaging; `checkPluginVersion` below stops the build if META-INF/plugin.xml
// still carries the old literal.
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
}

// Target IDE. Bump as needed; the plugin is binary-compatible upward.
intellij {
    version.set("2023.2")
    type.set("IC") // Community Edition
    plugins.set(listOf())
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Same stack the reference plugin (chapter_reader 3.0.6) ships with.
    implementation("org.jsoup:jsoup:1.14.2")            // HTML parsing + CSS selectors
    implementation("com.alibaba:fastjson:1.2.78")       // reading the remote site config JSON

    // epublib is not on Maven Central any more; the same 3.1 artifacts the reference
    // plugin bundles are kept in libs/ so the build stays reproducible and offline.
    implementation(files("libs/epublib-core-3.1.jar"))
    implementation(files("libs/kxml2-2.3.0.jar"))
    implementation(files("libs/xmlpull-1.1.3.4d_b4_min.jar"))
    implementation(files("libs/slf4j-api-1.6.1.jar"))
    implementation(files("libs/slf4j-simple-1.6.1.jar"))

    compileOnly("org.jetbrains:annotations:24.0.1")
}

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs = freeCompilerArgs + "-Xjsr305=strict"
        }
    }

    buildSearchableOptions {
        enabled = false
    }

    // The Kotlin stdlib and the three libraries above are bundled into the
    // plugin distribution automatically by the IntelliJ Gradle plugin
    // (`buildPlugin` writes them to <plugin>/lib/).
    patchPluginXml {
        sinceBuild.set("231")
        untilBuild.set(provider { null })
    }

    // Pure-logic verification of the reader (selectors, wrappers, splitters and
    // the real 798 KB site configuration). Runs on a plain JVM: it only touches
    // classes that do not depend on the IntelliJ Platform.
    register<JavaExec>("verifyReader") {
        group = "verification"
        description = "Run the reader's standalone logic verification"
        mainClass.set("com.bookreader.reader.verify.VerifyMainKt")
        classpath = sourceSets["test"].runtimeClasspath
        args = listOf(
            (rootProject.projectDir.parentFile.resolve("chapter_reader/chapter_config-v1.json")).absolutePath,
            (rootProject.projectDir.resolve("build/epubtest")).absolutePath,
        )
    }

    // Guard against the version drifting between the build and the descriptor.
    // `patchPluginXml` rewrites the *built* plugin.xml, which would silently hide
    // a stale literal in the source, so the source is checked explicitly.
    register("checkPluginVersion") {
        group = "verification"
        description = "Fail when META-INF/plugin.xml declares a different version than the build"

        val descriptor = layout.projectDirectory.file("src/main/resources/META-INF/plugin.xml").asFile
        val expected = version.toString()
        inputs.file(descriptor)
        inputs.property("expectedVersion", expected)

        doLast {
            val declared = Regex("<version>([^<]+)</version>")
                .find(descriptor.readText(Charsets.UTF_8))
                ?.groupValues?.get(1)

            if (declared != expected) {
                throw GradleException(
                    "plugin.xml declares version '$declared' but the build is '$expected'.\n" +
                        "Bump pluginVersion in gradle.properties and update " +
                        "src/main/resources/META-INF/plugin.xml to match.",
                )
            }
        }
    }

    named("buildPlugin") { dependsOn("checkPluginVersion") }
}
