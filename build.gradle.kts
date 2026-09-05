import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    java
}

group = "fr.codinbox.echo"
version = "7.1.0"

subprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        maven("https://nexus.codinbox.fr/repository/maven-public/")
    }

    plugins.withId("java") {
        the<JavaPluginExtension>().toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }

        dependencies {
            "testImplementation"(platform("org.junit:junit-bom:5.10.2"))
            "testImplementation"("org.junit.jupiter:junit-jupiter")
            "testImplementation"("org.assertj:assertj-core:3.25.3")
            "testImplementation"("org.mockito:mockito-core:5.23.0")
            "testImplementation"("org.mockito:mockito-junit-jupiter:5.23.0")
            "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        }

        tasks.withType<Test> {
            useJUnitPlatform()
            testClassesDirs = project.extensions.getByType<SourceSetContainer>()["test"].output.classesDirs
            classpath = project.extensions.getByType<SourceSetContainer>()["test"].runtimeClasspath
            useJUnitPlatform { excludeTags("benchmark", "load", "capacity") }
        }

        tasks.register<Test>("unitTest") {
            useJUnitPlatform { includeTags("unit") }
        }

        tasks.register<Test>("integrationTest") {
            useJUnitPlatform { includeTags("integration") }
        }

        tasks.register<Test>("e2eTest") {
            useJUnitPlatform { includeTags("e2e") }
        }
    }

    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    if (project.name !in setOf("paper", "velocity"))
                        from(components["java"])
                }
            }
            repositories {
                maven("https://nexus.codinbox.fr/repository/maven-releases/") {
                    name = "public-releases"
                    credentials {
                        username = System.getenv("MAVEN_USERNAME")
                        password = System.getenv("MAVEN_PASSWORD")
                    }
                }
            }
        }
    }
}
