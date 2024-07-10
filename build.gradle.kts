plugins {
    java
}

group = "fr.codinbox.echo"
version = "2.0.0"

repositories {
}

dependencies {
}

subprojects {
    repositories {
        maven("https://nexus.codinbox.fr/repository/maven-public/")
    }
}