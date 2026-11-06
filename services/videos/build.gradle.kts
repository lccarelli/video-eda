plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("org.jlleitschuh.gradle.ktlint")
    jacoco
}

val projectJdkVersion = 25
val detektRunnerJdkVersion = 21
val detektMaxSupportedJvmTarget = "22"
val minimumInstructionCoverage = "0.90"
val minimumBranchCoverage = "0.70"

java { toolchain { languageVersion = JavaLanguageVersion.of(projectJdkVersion) } }
kotlin {
    compilerOptions { optIn.add("kotlin.uuid.ExperimentalUuidApi") }
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation(platform("tools.jackson:jackson-bom:3.2.4")) {
        because(
            "jackson-core y jackson-databind 3.1.5 tienen CVE-2026-89407, -89425, -68497, -91776 y -91777; quitar cuando Spring Boot incluya 3.1.7 o posterior",
        )
    }
    constraints {
        implementation("org.apache.tomcat.embed:tomcat-embed-core:11.0.27") {
            because(
                "tomcat-embed-core 11.0.24 tiene CVE-2026-65182, -65905 y -68525 (críticos); quitar cuando Spring Boot incluya 11.0.25 o posterior",
            )
        }
        implementation("org.apache.tomcat.embed:tomcat-embed-el:11.0.27") {
            because("va con la misma versión que tomcat-embed-core")
        }
        implementation("org.apache.tomcat.embed:tomcat-embed-websocket:11.0.27") {
            because("va con la misma versión que tomcat-embed-core")
        }
        implementation("org.xerial.snappy:snappy-java:1.1.10.12") {
            because("snappy-java 1.1.10.7, que trae kafka-clients, tiene CVE-2026-108106 y CVE-2026-93451")
        }
        implementation("at.yawk.lz4:lz4-java:1.12.1") {
            because("lz4-java 1.10.2, que trae kafka-clients, tiene CVE-2026-106451")
        }
    }
    implementation("org.apache.kafka:kafka-clients:4.4.0")
    implementation("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.module:jackson-module-kotlin")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.22.4")
    testImplementation("com.networknt:json-schema-validator:3.0.8")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.postgresql:postgresql")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("ch.qos.logback:logback-classic")
}

jacoco {
    toolVersion = "0.8.15"
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "INSTRUCTION"
                minimum = minimumInstructionCoverage.toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = minimumBranchCoverage.toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

tasks.bootJar {
    archiveFileName = "videos.jar"
}

ktlint {
    version.set("1.8.0")
}

val detektCli = configurations.create("detektCli")

dependencies {
    detektCli("io.gitlab.arturbosch.detekt:detekt-cli:1.23.8")
}

val detekt = tasks.register<JavaExec>("detekt") {
    group = "verification"
    description = "Análisis estático con detekt 1.23.8 en un proceso JDK $detektRunnerJdkVersion."
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(detektRunnerJdkVersion) }
    classpath = detektCli
    mainClass = "io.gitlab.arturbosch.detekt.cli.Main"
    val config = rootProject.file("config/detekt/detekt.yml")
    val upToDateMarker = layout.buildDirectory.file("detekt/ok")
    inputs.files(fileTree("src"), config)
    outputs.file(upToDateMarker)
    args(
        "--input",
        "src/main/kotlin,src/test/kotlin",
        "--config",
        config.path,
        "--build-upon-default-config",
        "--jvm-target",
        detektMaxSupportedJvmTarget,
    )
    doLast { upToDateMarker.get().asFile.apply { parentFile.mkdirs() }.writeText("ok") }
}

tasks.check {
    dependsOn(detekt)
}
