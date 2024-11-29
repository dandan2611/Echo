plugins {
    java
    `java-library`
}

group = "fr.codinbox.echo"
version = "4.0.0"

repositories {
    mavenCentral()
}

dependencies {
    api(project(":api"))
    compileOnlyApi("fr.codinbox.connector:commons:6.0.0")
}