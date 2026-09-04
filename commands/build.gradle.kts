plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":api"))
    implementation(project(":queue"))
    implementation(project(":ondemand"))
    api("org.incendo:cloud-annotations:2.1.0")
    api("net.kyori:adventure-api:4.17.0")

    testImplementation("net.kyori:adventure-text-serializer-plain:4.17.0")
}

java {
    withSourcesJar()
    withJavadocJar()
}
