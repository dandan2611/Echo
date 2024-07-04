plugins {
    java
}

group = "fr.codinbox.echo"
version = "0.0.1-SNAPSHOT"

repositories {
}

dependencies {
}

subprojects {
    repositories {
        maven("https://nexus.codinbox.fr/repository/maven-public/")
    }
}