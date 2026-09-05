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
    implementation(project(":queue"))
    implementation("org.incendo:cloud-paper:2.0.0")
    compileOnlyApi("fr.codinbox.connector:paper:6.0.0")

    compileOnlyApi("io.papermc.paper:paper-api:26.2.build.121-stable")
    testImplementation("io.papermc.paper:paper-api:26.2.build.121-stable")

    testImplementation("fr.codinbox.connector:commons:6.0.0")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.testcontainers:junit-jupiter")
}

tasks {
    withType<Test>().configureEach {
        dependsOn(shadowJar)
        doFirst { systemProperty("echo.paper.jar", shadowJar.get().archiveFile.get().asFile.absolutePath) }
    }
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
        filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
        doLast {
            check(zipTree(archiveFile.get().asFile).matching {
                include("com/fasterxml/jackson/**")
            }.files.isEmpty()) { "Echo must use Connector's Jackson classes" }
        }
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

publishing.publications.named<MavenPublication>("maven") {
    setArtifacts(listOf(tasks.shadowJar))
    artifact(tasks.named("sourcesJar"))
    artifacts.matching { it.classifier == "all" }.all { classifier = null }
}
