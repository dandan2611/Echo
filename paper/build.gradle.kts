plugins {
    java
    `java-library`
    id("io.papermc.paperweight.userdev") version "1.7.1"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "fr.codinbox.echo"
version = "2.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation(project(":core"))
    compileOnlyApi("fr.codinbox.connector:paper:6.0.0")

    paperweight.paperDevBundle("1.20.1-R0.1-SNAPSHOT")
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
