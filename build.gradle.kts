import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.GenerateLexerTask
import org.jetbrains.intellij.platform.gradle.tasks.GenerateParserTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile


val versionDetails: groovy.lang.Closure<com.palantir.gradle.gitversion.VersionDetails> by extra

plugins {
    id("java")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.gradleIntellijPlugin)
    alias(libs.plugins.intellijPlatformGrammarKit)
    alias(libs.plugins.changelog)
    alias(libs.plugins.gitVersion)
}

group = providers.gradleProperty("pluginGroup").get()

val gitDetails = versionDetails()
version = if (gitDetails.isCleanTag) {
    gitDetails.version
} else {
    // eg. 0.1.4-dev.12345678
    providers.gradleProperty("intellijGnVersion").get().ifEmpty {
        throw IllegalStateException("intellijGnVersion must be set in gradle.properties")
    } + "-dev." + gitDetails.gitHash
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion")) {
            useInstaller = false
        }
        grammarKit("2022.3.2")
        jflex("1.9.2")

        bundledPlugins(
            providers.gradleProperty("platformBundledPlugins").map {
                it.split(',').map(String::trim).filter(String::isNotEmpty)
            },
        )
        plugins(
            providers.gradleProperty("platformPlugins").map {
                it.split(',').map(String::trim).filter(String::isNotEmpty)
            },
        )
        bundledModules(
            providers.gradleProperty("platformBundledModules").map {
                it.split(',').map(String::trim).filter(String::isNotEmpty)
            },
        )
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

tasks.register("generateLexerTask", GenerateLexerTask::class) {
    // source flex file
    sourceFile.set(file("src/grammar/gn.flex"))

    // target directory for lexer
    targetOutputDir = layout.buildDirectory.dir("generated/grammarkit/lexer/com/google/idea/gn").get().asFile

    // if set, plugin will remove a lexer output file before generating new one. Default: false
    purgeOldFiles = true
}

tasks.register("generateParserTask", GenerateParserTask::class) {
    // source bnf file
    sourceFile.set(file("src/grammar/gn.bnf"))

    // optional, task-specific root for the generated files. Default: none
    targetRootOutputDir = layout.buildDirectory.dir("generated/grammarkit/parser").get().asFile

    // path to a parser file, relative to the targetRoot
    pathToParser = "/com/google/idea/gn/parser/GnParser.java"

    // path to a directory with generated psi files, relative to the targetRoot
    pathToPsiRoot = "/com/google/idea/gn/psi"

    // if set, plugin will remove a parser output file and psi output directory before generating new ones. Default: false
    purgeOldFiles = true
}

intellijPlatform {
    sandboxContainer.set(file("tmp/sandbox"))

    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = project.provider { project.version.toString() }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
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
            recommended()
        }
    }

    publishing {
        token = providers.environmentVariable("ORG_GRADLE_PROJECT_intellijPublishToken")
    }
}

changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
    versionPrefix = ""
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
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    if (enableWarningAsError) {
        compilerOptions.allWarningsAsErrors.set(true)
    }
}

sourceSets {
    main {
        java {
            srcDir(layout.buildDirectory.dir("generated/grammarkit/parser"))
            srcDir(layout.buildDirectory.dir("generated/grammarkit/lexer"))
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks {
    named("compileKotlin") {
        dependsOn("generateLexerTask", "generateParserTask")
    }
    named("compileJava") {
        dependsOn("generateLexerTask", "generateParserTask")
    }

    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }
}
