plugins {
    java
}

group = "fr.codinbox.echo"
version = "5.0.0"

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
    }
}