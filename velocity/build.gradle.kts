plugins {
    java
    `java-library`
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "fr.codinbox.echo"
version = "1.0.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation(project(":core"))
    compileOnlyApi("fr.codinbox.connector:velocity:6.0.0")

    compileOnly("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
}

tasks {
    build {
        dependsOn("shadowJar")
    }

    jar {
        archiveBaseName.set("echo-velocity")
    }

    shadowJar {
        archiveBaseName.set("echo-velocity")
    }
}