plugins {
    `java-library`
    id("io.papermc.paperweight.userdev") version "1.7.1"
    id("com.gradleup.shadow") version "8.3.9"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":commands"))
    implementation(project(":agones")) {
        exclude(group = "io.fabric8")
    }
    implementation(project(":queue"))
    implementation("org.incendo:cloud-paper:2.0.0")
    compileOnlyApi("fr.codinbox.connector:paper:6.0.0")

    paperweight.paperDevBundle("1.20.6-R0.1-SNAPSHOT")

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
        exclude("**/AgonesOnDemandServers*.class")
        exclude("com/fasterxml/jackson/**")
        relocate("org.incendo.cloud", "fr.codinbox.echo.paper.libs.cloud")
        relocate("io.leangen.geantyref", "fr.codinbox.echo.paper.libs.geantyref")
        mergeServiceFiles()
        doLast {
            check(zipTree(archiveFile.get().asFile).matching {
                include("com/fasterxml/jackson/**")
            }.files.isEmpty()) { "Echo must use Connector's Jackson classes" }
        }
    }
}
