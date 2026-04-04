plugins {
    java
    `java-library`
    `maven-publish`
}

group = "fr.codinbox.echo"
version = "5.3.0"

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

    compileOnly("org.redisson:redisson:3.32.0")

    testCompileOnly("org.projectlombok:lombok:1.18.32")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.32")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.11.0")
}

tasks.test {
    useJUnitPlatform()
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
    repositories {
        maven("https://nexus.codinbox.fr/repository/maven-releases/") {
            name = "public-releases"
            credentials {
                username = System.getenv("MAVEN_USERNAME")
                password = System.getenv("MAVEN_PASSWORD")
            }
        }
    }
}
