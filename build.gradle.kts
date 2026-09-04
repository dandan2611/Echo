import org.gradle.api.tasks.SourceSetContainer
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    java
}

group = "fr.codinbox.echo"
version = "6.1.1"

repositories {
}

dependencies {
}

subprojects {
    repositories {
        maven("https://nexus.codinbox.fr/repository/maven-public/")
    }

    plugins.withId("java") {
        apply(plugin = "jacoco")

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

        tasks.register<JacocoReport>("unitTestCoverageReport") {
            dependsOn("unitTest")
            executionData(project.layout.buildDirectory.file("jacoco/unitTest.exec"))
            sourceSets(project.the<SourceSetContainer>()["main"])
            reports {
                html.required.set(true)
                xml.required.set(true)
            }
        }

        val featureCoverageClasses = when (project.name) {
            "api" -> listOf(
                    "fr.codinbox.echo.api.EchoConfig",
                    "fr.codinbox.echo.api.EchoConfig\$Builder",
                    "fr.codinbox.echo.api.administration.RemoteAdministration",
                    "fr.codinbox.echo.api.messaging.impl.ResourceControlRequest",
                    "fr.codinbox.echo.api.messaging.impl.ResourceControlRequest\$Response",
                    "fr.codinbox.echo.api.messaging.impl.UserDisconnectRequest",
                    "fr.codinbox.echo.api.messaging.impl.UserDisconnectRequest\$Response",
                    "fr.codinbox.echo.api.server.ServerLoad",
                    "fr.codinbox.echo.api.server.ServerLoadSnapshot",
                    "fr.codinbox.echo.api.server.placement.ServerPlacement\$ActiveReservation",
                    "fr.codinbox.echo.api.server.placement.ServerPlacement\$CandidateEvaluation",
                    "fr.codinbox.echo.api.server.placement.ServerPlacement\$Explanation",
                    "fr.codinbox.echo.api.server.placement.ServerPlacement\$Request",
                    "fr.codinbox.echo.api.server.placement.ServerPlacement\$Reservation",
                    "fr.codinbox.echo.api.server.placement.ServerPlacement\$ServerStatus"
            )
            "core" -> listOf(
                    "fr.codinbox.echo.core.messaging.provider.RedisMessagingProvider",
                    "fr.codinbox.echo.core.server.ServerLoadManagerImpl",
                    "fr.codinbox.echo.core.server.ServerLoadManagerImpl\$Registration",
                    "fr.codinbox.echo.core.server.placement.RedisServerPlacement",
                    "fr.codinbox.echo.core.user.UserImpl"
            )
            "commands" -> listOf(
                    "fr.codinbox.echo.commands.CommandFormatter",
                    "fr.codinbox.echo.commands.EchoCommands"
            )
            "ondemand" -> listOf(
                    "fr.codinbox.echo.ondemand.OnDemandAdministration\$Allocation",
                    "fr.codinbox.echo.ondemand.OnDemandAdministration\$Reconciliation",
                    "fr.codinbox.echo.ondemand.internal.OnDemandServersRegistry"
            )
            "paper" -> listOf(
                    "fr.codinbox.echo.paper.event.ServerDrainEvent",
                    "fr.codinbox.echo.paper.listener.JoinListener",
                    "fr.codinbox.echo.paper.messaging.QueuePlacementPrepareRequestHandler",
                    "fr.codinbox.echo.paper.messaging.ResourceControlRequestHandler"
            )
            "queue" -> listOf(
                    "fr.codinbox.echo.queue.QueueAdministration\$QueueOverview",
                    "fr.codinbox.echo.queue.QueueAdministration\$QueueTicket",
                    "fr.codinbox.echo.queue.QueueDefinition",
                    "fr.codinbox.echo.queue.QueueId",
                    "fr.codinbox.echo.queue.QueueOptions",
                    "fr.codinbox.echo.queue.QueuePlacementAssignment",
                     "fr.codinbox.echo.queue.QueuePlacementPreparer\$Decision",
                     "fr.codinbox.echo.queue.QueueRequest",
                     "fr.codinbox.echo.queue.QueueRequestStatus",
                     "fr.codinbox.echo.queue.QueueService",
                     "fr.codinbox.echo.queue.internal.QueueServiceRegistry",
                     "fr.codinbox.echo.queue.redis.RedisQueue",
                    "fr.codinbox.echo.queue.redis.RedisQueueStore"
            )
            "velocity" -> listOf(
                    "fr.codinbox.echo.velocity.messaging.ResourceControlRequestHandler",
                    "fr.codinbox.echo.velocity.messaging.ServerSwitchRequestHandler",
                    "fr.codinbox.echo.velocity.messaging.UserDisconnectRequestHandler"
            )
            else -> emptyList()
        }

        if (featureCoverageClasses.isNotEmpty()) {
            val unitTestCoverageVerification = tasks.register<JacocoCoverageVerification>(
                    "unitTestCoverageVerification") {
                dependsOn("unitTest")
                executionData(project.layout.buildDirectory.file("jacoco/unitTest.exec"))
                sourceSets(project.the<SourceSetContainer>()["main"])
                violationRules {
                    rule {
                        element = "CLASS"
                        includes = featureCoverageClasses
                        limit {
                            counter = "LINE"
                            value = "COVEREDRATIO"
                            minimum = "0.95".toBigDecimal()
                        }
                        limit {
                            counter = "BRANCH"
                            value = "COVEREDRATIO"
                            minimum = "0.90".toBigDecimal()
                        }
                    }
                }
            }
            tasks.named("check") {
                dependsOn(unitTestCoverageVerification)
            }
        }
    }
}
