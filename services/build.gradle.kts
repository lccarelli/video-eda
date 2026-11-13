plugins {
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.spring") version "2.4.21" apply false
    id("org.springframework.boot") version "4.1.2" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
    id("org.sonarqube") version "7.5.0.8588"
}

sonar {
    properties {
        property("sonar.projectKey", "video-eda")
        property("sonar.projectName", "video-eda")
    }
}

project(":videos") {
    sonar {
        properties {
            property("sonar.coverage.jacoco.xmlReportPaths", "build/reports/jacoco/test/jacocoTestReport.xml")
        }
    }
}

subprojects {
    repositories { mavenCentral() }
}
