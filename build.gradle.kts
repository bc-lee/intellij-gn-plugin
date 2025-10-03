import org.jetbrains.grammarkit.tasks.GenerateLexerTask
import org.jetbrains.grammarkit.tasks.GenerateParserTask
import org.jetbrains.changelog.Changelog
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jetbrains.intellij.platform.gradle.TestFrameworkType


val versionDetails: groovy.lang.Closure<com.palantir.gradle.gitversion.VersionDetails> by extra

plugins {
    id("java")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.gradleIntellijPlugin)
    alias(libs.plugins.grammerKit)
    alias(libs.plugins.changelog)
    alias(libs.plugins.gitVersion)
}

val gitDetails = versionDetails()
version = if (gitDetails.isCleanTag) {
    gitDetails.version
} else {
    // eg. 0.1.4-dev.12345678
    providers.gradleProperty("intellijGnVersion").get().ifEmpty {
        throw IllegalStateException("intellijGnVersion must be set in gradle.properties")
    } + "-dev." + gitDetails.gitHash
}

java.sourceCompatibility = JavaVersion.VERSION_17
java.targetCompatibility = JavaVersion.VERSION_17

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        @Suppress("DEPRECATION")
        intellijIdeaCommunity("2023.1")
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinTest)
    testImplementation(libs.kotlinTestJdk7)
    testImplementation(platform(libs.junitBom))
    testRuntimeOnly(libs.junitPlatformLauncher) {
        because("Only needed to run tests in a version of IntelliJ IDEA that bundles older versions")
    }
    testRuntimeOnly(libs.junitJupiterEngine)
    testRuntimeOnly(libs.jnintVintageEngine)
}

grammarKit {
    grammarKitRelease.set("2021.1.2")
}

tasks.register("generateLexerTask", GenerateLexerTask::class) {
    // source flex file
    sourceFile.set(file("src/grammar/gn.flex"))

    // target directory for lexer
    targetOutputDir = project.layout.projectDirectory.dir("src/gen/com/google/idea/gn")

    // if set, plugin will remove a lexer output file before generating new one. Default: false
    purgeOldFiles = true
}

tasks.register("generateParserTask", GenerateParserTask::class) {
    // source bnf file
    sourceFile.set(file("src/grammar/gn.bnf"))

    // optional, task-specific root for the generated files. Default: none
    targetRootOutputDir = project.layout.projectDirectory.dir("src/gen")

    // path to a parser file, relative to the targetRoot
    pathToParser = "/com/google/idea/gn/parser/GnParser.java"

    // path to a directory with generated psi files, relative to the targetRoot
    pathToPsiRoot = "/com/google/idea/gn/psi"

    // if set, plugin will remove a parser output file and psi output directory before generating new ones. Default: false
    purgeOldFiles = true
}

val intellijSinceBuild = "231"
val intellijUntilBuild = ""

intellijPlatform {
    sandboxContainer.set(file("tmp/sandbox"))

    pluginConfiguration {
        ideaVersion {
            sinceBuild.set(intellijSinceBuild)
            if (intellijUntilBuild.isNotBlank()) {
                untilBuild.set(intellijUntilBuild)
            }
        }

        val changelog = project.changelog // local variable for configuration cache compatibility
        changeNotes.set(providers.gradleProperty("intellijGnVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        })
    }

    pluginVerification {
        ides {
            // IC (IntelliJ IDEA Community) is no longer available starting from 2025.3 (build 253)
            select {
                sinceBuild.set(intellijSinceBuild)
                untilBuild.set("252.*")
            }

            // TODO: Replace EAP with the release version when available
            create("IU", "253.22441.33")
        }
    }
}

changelog {
    groups.empty()
}

tasks.named("compileKotlin") {
    setDependsOn(listOf(tasks.named("generateLexerTask"), tasks.named("generateParserTask")))
}

tasks.withType<JavaCompile> {
    val enableWarningAsError = project.findProperty("enableWarningAsError")?.toString()?.toBoolean() ?: false
    if (enableWarningAsError) {
        var compilerArgs = options.compilerArgs
        if (compilerArgs == null) {
            compilerArgs = mutableListOf()
        }
        compilerArgs.add("-Werror")
        options.compilerArgs = compilerArgs
    }
}

tasks.withType<KotlinJvmCompile> {
    val enableWarningAsError = project.findProperty("enableWarningAsError")?.toString()?.toBoolean() ?: false
    kotlinOptions.jvmTarget = "17"
    if (enableWarningAsError) {
        kotlinOptions.allWarningsAsErrors = true
    }
}

sourceSets {
    main {
        java {
            srcDir("src/gen")
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.publishPlugin {
    token = providers.environmentVariable("ORG_GRADLE_PROJECT_intellijPublishToken")
}

// Helper build task to create a local updatePlugins.xml file to serve updates
// locally.
tasks.register("serverPlugins") {
    dependsOn(tasks.named("buildPlugin"))
    group = "intellij"
    doLast {
      File(layout.buildDirectory.asFile.get(), "distributions/updatePlugins.xml").writeText("""<?xml version="1.0" encoding="UTF-8"?>
<plugins>
    <<plugin id="com.google.idea.gn" url="http://localhost:8080/gn-${version}.zip" version="$version">
      <name>GN</name>
      <description>Experimental GN plugin for intellij</description>
    <idea-version since-build="$intellijSinceBuild" />
  </plugin>
</plugins>
""".trimIndent())
    }
}
