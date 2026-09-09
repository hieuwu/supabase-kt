@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnLockMismatchReport
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension

val excludedModules = listOf("plugins", "serializers", "test-common", "integration-test")

private val libraryFilter = { withFilter: Boolean ->
    allprojects.filter { it.name !in excludedModules && !it.path.contains("sample") && if(withFilter) true else it.name != "bom" && it.name != it.rootProject.name }
}

fun libraryModules(withBom: Boolean = true, init: Project.() -> Unit) = configure(
    libraryFilter(withBom),
    init
)

// Every published module. Unlike libraryFilter(false) this keeps the core module, whose name
// collides with the root project's name.
val analyzedModules = libraryFilter(true).filter { it != rootProject }

plugins {
    id(libs.plugins.kotlin.multiplatform.get().pluginId) apply false
    id(libs.plugins.android.kotlin.multiplatform.library.get().pluginId) apply false
    id(libs.plugins.detekt.get().pluginId) apply false
    id(libs.plugins.dokka.get().pluginId)
    alias(libs.plugins.kotlinx.plugin.serialization) apply false
    id(libs.plugins.maven.publish.get().pluginId) apply false
    id(libs.plugins.power.assert.get().pluginId) apply false
    alias(libs.plugins.sonarqube)
    alias(libs.plugins.kover) apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

dependencies {
    libraryFilter(false).forEach {
        dokka(project(it.path))
    }
}

libraryModules {
    apply(plugin = "org.jetbrains.dokka")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    apply(plugin = "com.vanniktech.maven.publish")

    group = extra["base-group"].toString()
    version = supabaseVersion

    applyPublishing()
}

val reportMerge by tasks.registering(io.gitlab.arturbosch.detekt.report.ReportMergeTask::class) {
    output.set(rootProject.layout.buildDirectory.file("reports/detekt/merge.sarif"))
}

libraryModules(false) {
    applyDokkaWithConfiguration()
    applyPowerAssertConfiguration()
    applyDetektWithConfiguration(reportMerge)
}

// Kover measures coverage on the JVM target of every published module. The reports are kept per
// module instead of aggregated, because the root project shares its coordinates with the core module,
// which makes Gradle collapse the two into a single, empty aggregation entry.
configure(analyzedModules) {
    apply(plugin = "org.jetbrains.kotlinx.kover")
}

val coverageReports = analyzedModules.map { it.layout.buildDirectory.file("reports/kover/report.xml").get().asFile }

tasks.register("koverXmlReportAll") {
    description = "Generates the Kover XML coverage report of every published module."
    dependsOn(analyzedModules.map { "${it.path}:koverXmlReport" })
}

tasks.register("koverHtmlReportAll") {
    description = "Generates the Kover HTML coverage report of every published module."
    dependsOn(analyzedModules.map { "${it.path}:koverHtmlReport" })
}

tasks.register("detektAll") {
    libraryModules(false) {
        this@register.dependsOn(tasks.withType<io.gitlab.arturbosch.detekt.Detekt>())
    }
}

// Configure Gradle Task to build all sample submodules at once
configure(allprojects.filter { it.parent?.name == "sample" }) {
    val children = this.childProjects
    this.tasks.register("buildAll") {
        children.values.forEach { child ->
            this.dependsOn(child.tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>())
        }
    }
}

rootProject.plugins.withType(org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin::class.java) {
    rootProject.the<YarnRootExtension>().yarnLockMismatchReport =
        YarnLockMismatchReport.WARNING
    rootProject.the<YarnRootExtension>().reportNewYarnLock = false
    rootProject.the<YarnRootExtension>().yarnLockAutoReplace = true
}

// Sonar analysis. Host, organization and project key are read from Gradle properties first and
// environment variables second, so the same configuration works against both SonarQube Cloud
// (https://sonarcloud.io, needs an organization) and a self-hosted SonarQube Server (no organization).
// Unset repository variables reach the build as empty environment variables, so blank values fall back too.
fun sonarSetting(gradleProperty: String, environmentVariable: String, default: String? = null): String? =
    providers.gradleProperty(gradleProperty).orNull?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(environmentVariable).orNull?.takeIf { it.isNotBlank() }
        ?: default

// Directories detekt writes its XML reports to. The files themselves are only listed once Sonar runs,
// which is after detekt has produced them.
val detektReportDirs = libraryFilter(false).map { it.layout.buildDirectory.dir("reports/detekt").get().asFile }

sonar {
    properties {
        property("sonar.projectKey", sonarSetting("sonar.projectKey", "SONAR_PROJECT_KEY", rootProject.name)!!)
        property("sonar.projectName", rootProject.name)
        property("sonar.projectVersion", supabaseVersion)
        property("sonar.host.url", sonarSetting("sonar.host.url", "SONAR_HOST_URL", "https://sonarcloud.io")!!)
        sonarSetting("sonar.organization", "SONAR_ORGANIZATION")?.let { property("sonar.organization", it) }
        property("sonar.sourceEncoding", "UTF-8")
        // The Kotlin analyzer works on sources, so there is no need to make Sonar trigger compilation.
        property("sonar.gradle.skipCompile", "true")
        property("sonar.coverage.jacoco.xmlReportPaths", coverageReports.joinToString(",") { it.absolutePath })
        val detektReports = detektReportDirs
            .flatMap { it.listFiles { file -> file.extension == "xml" }?.toList().orEmpty() }
        if (detektReports.isNotEmpty()) {
            property("sonar.kotlin.detekt.reportPaths", detektReports.joinToString(",") { it.absolutePath })
        }
    }
}

// Sonar reads the coverage and detekt reports from disk, so they have to be written before it runs.
tasks.named("sonar") {
    dependsOn("koverXmlReportAll", "detektAll")
}

// Only the published library modules are analyzed. Their parents are kept as well, because Sonar
// skips the children of a skipped project.
val analyzedProjects = analyzedModules
    .flatMap { generateSequence(it) { project -> project.parent } }
    .toSet()

configure(allprojects.filterNot { it in analyzedProjects }) {
    extensions.configure<org.sonarqube.gradle.SonarExtension>(org.sonarqube.gradle.SonarExtension.SONAR_EXTENSION_NAME) {
        isSkipProject = true
    }
}
