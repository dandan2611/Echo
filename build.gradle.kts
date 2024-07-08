plugins {
    java
}

group = "fr.codinbox.echo"
version = "1.0.1"

repositories {
}

dependencies {
}

subprojects {
    repositories {
        maven("https://nexus.codinbox.fr/repository/maven-public/")
    }
}