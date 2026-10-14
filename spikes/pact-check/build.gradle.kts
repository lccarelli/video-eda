plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
    id("org.springframework.boot") version "4.1.1"
}

repositories { mavenCentral() }

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
kotlin { compilerOptions { freeCompilerArgs.add("-Xjsr305=strict") } }

val pactVersion = "4.7.5"

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("au.com.dius.pact.consumer:junit5:$pactVersion")
    testImplementation("au.com.dius.pact.provider:junit5:$pactVersion")
    testImplementation("com.networknt:json-schema-validator:3.0.8")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val pactDir = layout.buildDirectory.dir("pacts").get().asFile.absolutePath

tasks.test {
    useJUnitPlatform()

    exclude("**/VideosProvider*")
    systemProperty("pact.rootDir", pactDir)
    systemProperty("pact.writer.overwrite", "true")
    systemProperty("pact_do_not_track", "true")
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

fun Test.configureProvider() {
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty("pact_do_not_track", "true")

    project.findProperty("break")?.let { systemProperty("videoeda.break", it.toString()) }
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

val providerVerification = tasks.register<Test>("providerVerification") {
    group = "verification"
    configureProvider()
    include("**/VideosProviderVerificationTest*")
    dependsOn(tasks.test)
}
tasks.test { finalizedBy(providerVerification) }

tasks.register<Test>("providerBrokerVerification") {
    group = "verification"
    configureProvider()
    include("**/VideosProviderBrokerVerificationTest*")
    val brokerUrl = project.findProperty("pactBrokerUrl")?.toString() ?: ""
    systemProperty("pactbroker.url", brokerUrl)
    systemProperty("pact.verifier.publishResults", "true")
    systemProperty("pact.provider.version", project.findProperty("providerVersion")?.toString() ?: "0.0.0")
    systemProperty("pact.provider.branch", project.findProperty("providerBranch")?.toString() ?: "main")
}
