plugins {
    java
}

group = "fr.codinbox.echo"
version = "4.0.0"

repositories {
}

dependencies {
}

subprojects {
    repositories {
        maven("https://nexus.codinbox.fr/repository/maven-public/")
    }
}