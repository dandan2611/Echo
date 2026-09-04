plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":api"))
    api(project(":ondemand"))
    compileOnlyApi("fr.codinbox.connector:commons:6.0.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")

    testImplementation("org.redisson:redisson:3.32.0")
    testImplementation("fr.codinbox.connector:commons:6.0.0")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.testcontainers:junit-jupiter")
}

java {
    withSourcesJar()
    withJavadocJar()
}
