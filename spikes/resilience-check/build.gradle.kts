plugins {
    kotlin("jvm") version "2.4.21"
}

repositories { mavenCentral() }

val kafkaClientsVersion = (findProperty("kafka") as String?) ?: "4.3.1"

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
kotlin {
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
}

dependencies {
    implementation("org.postgresql:postgresql:42.7.14")
    implementation("org.apache.kafka:kafka-clients:${kafkaClientsVersion}")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testImplementation("org.testcontainers:testcontainers-kafka:2.0.5")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.20")
}

tasks.test {
    useJUnitPlatform()

    (findProperty("kafkaImage") as String?)?.let { environment("KAFKA_IMAGE", it) }

    maxParallelForks = 1
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
