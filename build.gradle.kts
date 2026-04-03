plugins {
    java
}

group = "fr.codinbox.echo"
version = "5.1.0"

repositories {
}

dependencies {
}

subprojects {
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
            "testImplementation"("org.mockito:mockito-core:5.11.0")
            "testImplementation"("org.mockito:mockito-junit-jupiter:5.11.0")
        }

        tasks.withType<Test> {
            useJUnitPlatform()
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
}