plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
    id("org.springframework.boot") version "4.1.1"
}

repositories { mavenCentral() }

val kafkaClientsVersion = (findProperty("kafka") as String?) ?: "4.3.1"

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
kotlin { compilerOptions { freeCompilerArgs.add("-Xjsr305=strict") } }

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.kafka:spring-kafka:4.1.1")

    implementation("org.apache.kafka:kafka-clients:${kafkaClientsVersion}")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers-kafka:2.0.5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()

    (findProperty("kafkaImage") as String?)?.let { environment("KAFKA_IMAGE", it) }
    environment("KAFKA_CLIENTS_EXPECTED", kafkaClientsVersion)
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
