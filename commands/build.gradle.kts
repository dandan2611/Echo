plugins {
    java
    `java-library`
    `maven-publish`
}

group = "fr.codinbox.echo"
version = "6.1.1"

dependencies {
    api(project(":api"))
    implementation(project(":queue"))
    implementation(project(":ondemand"))
    api("org.incendo:cloud-annotations:2.1.0")
    api("net.kyori:adventure-api:4.17.0")

    testImplementation("net.kyori:adventure-text-serializer-plain:4.17.0")
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
