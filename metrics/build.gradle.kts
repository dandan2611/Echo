plugins {
    `java-library`
    `maven-publish`
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":api"))
    // Queue metrics are exported only where a QueueService is loaded (lobbies).
    compileOnly(project(":queue"))
    api(platform("io.prometheus:prometheus-metrics-bom:1.9.0"))
    api("io.prometheus:prometheus-metrics-core")
    implementation("io.prometheus:prometheus-metrics-exporter-httpserver")
    implementation("io.prometheus:prometheus-metrics-instrumentation-jvm")

    testImplementation(project(":queue"))
}

java {
    withSourcesJar()
    withJavadocJar()
}
