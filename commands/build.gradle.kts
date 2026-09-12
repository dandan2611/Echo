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

// Compile against Adventure 4, but also exercise the platform-provided Adventure 5 ABI.
val adventure5Runtime = configurations.create("adventure5Runtime") {
    extendsFrom(configurations.testRuntimeClasspath.get())
    resolutionStrategy.eachDependency {
        if (requested.group == "net.kyori" && requested.name.startsWith("adventure-"))
            useVersion("5.2.0")
    }
}

val adventure5Test = tasks.register<Test>("adventure5Test") {
    classpath = sourceSets.main.get().output + sourceSets.test.get().output + adventure5Runtime
}

tasks.check { dependsOn(adventure5Test) }
