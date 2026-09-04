plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":api"))
}

java {
    withSourcesJar()
    withJavadocJar()
}
