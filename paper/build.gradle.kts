plugins {
    java
    `java-library`
    id("io.papermc.paperweight.userdev") version "1.7.1"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "fr.codinbox.echo"
version = "5.3.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation(project(":core"))
    compileOnlyApi("fr.codinbox.connector:paper:6.0.0")

    paperweight.paperDevBundle("1.20.1-R0.1-SNAPSHOT")

    testImplementation(project(":core"))
    testImplementation("fr.codinbox.connector:commons:6.0.0")
}

tasks {
    build {
        dependsOn("shadowJar")
    }

    jar {
        archiveBaseName.set("echo-paper")
    }

    shadowJar {
        archiveBaseName.set("echo-paper")
    }
}
