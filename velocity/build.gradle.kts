plugins {
    `java-library`
    `maven-publish`
    id("com.gradleup.shadow") version "9.6.1"
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
    implementation("org.incendo:cloud-annotations:2.1.0")
    implementation("org.incendo:cloud-velocity:2.0.0")
    compileOnlyApi("fr.codinbox.connector:velocity:6.0.0")

    compileOnly("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")

    testImplementation("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    testImplementation("fr.codinbox.connector:commons:6.0.0")
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
        exclude("**/AgonesOnDemandServers*.class")
        exclude("com/fasterxml/jackson/**")
        relocate("org.incendo.cloud", "fr.codinbox.echo.velocity.libs.cloud")
        relocate("io.leangen.geantyref", "fr.codinbox.echo.velocity.libs.geantyref")
        mergeServiceFiles()
        filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
        doLast {
            check(zipTree(archiveFile.get().asFile).matching {
                include("com/fasterxml/jackson/**")
            }.files.isEmpty()) { "Echo must use Connector's Jackson classes" }
        }
    }
}

java { withSourcesJar() }

publishing.publications.named<MavenPublication>("maven") {
    setArtifacts(listOf(tasks.shadowJar))
    artifact(tasks.named("sourcesJar"))
    artifacts.matching { it.classifier == "all" }.all { classifier = null }
}
