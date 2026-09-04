plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":api"))
    api(project(":ondemand"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    implementation("io.fabric8:kubernetes-client:7.8.0")
}

java {
    withSourcesJar()
    withJavadocJar()
}
