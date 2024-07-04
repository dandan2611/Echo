plugins {
    java
    `java-library`
    `maven-publish`
}

group = "fr.codinbox.echo"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    api("org.jetbrains:annotations:24.1.0")

    compileOnly("org.projectlombok:lombok:1.18.32")
    annotationProcessor("org.projectlombok:lombok:1.18.32")

    api("com.fasterxml.jackson.core:jackson-core:2.17.1")
    api("com.fasterxml.jackson.core:jackson-annotations:2.17.1")

    api("it.unimi.dsi:fastutil:8.5.13")
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}
