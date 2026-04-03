plugins {
    java
    `java-library`
}

group = "fr.codinbox.echo"
version = "5.2.0"

repositories {
    mavenCentral()
}

dependencies {
    api(project(":api"))
    compileOnlyApi("fr.codinbox.connector:commons:6.0.0")

    testImplementation("org.redisson:redisson:3.32.0")
    testImplementation("fr.codinbox.connector:commons:6.0.0")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.19.7"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testCompileOnly("org.projectlombok:lombok:1.18.32")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.32")
}