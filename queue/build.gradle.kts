plugins {
    java
    `java-library`
    `maven-publish`
}

group = "fr.codinbox.echo"
version = "6.1.1"

dependencies {
    api(project(":api"))
    api(project(":ondemand"))
    compileOnlyApi("fr.codinbox.connector:commons:6.0.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")

    testImplementation("org.redisson:redisson:3.32.0")
    testImplementation("fr.codinbox.connector:commons:6.0.0")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
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
