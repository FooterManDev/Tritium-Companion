plugins {
    `java-library`
    signing
    id("com.vanniktech.maven.publish") version "0.35.0"
}

group = "io.github.footermandev"
val artifactId = "tritium-mod-api"
version = "0.1.1"

java { withSourcesJar() }

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation("com.google.code.gson:gson:2.13.2")
}

mavenPublishing {
    coordinates(group.toString(), artifactId, version.toString())
    pom {
        name = "Tritium Mod API"
        description = "API for Tritium Launcher integration"
        inceptionYear = "2025"
        url = "https://github.com/FooterManDev/Tritium-Companion"
        licenses {
            license {
                name = "MIT"
                url = "https://opensource.org/licenses/MIT"
            }
        }
        developers {
            developer {
                id = "footermandev"
                name = "FooterManDev"
                url = "https://github.com/FooterManDev"
            }
        }
        scm {
            url = "https://github.com/FooterManDev/Tritium-Companion"
            connection = "scm:git:git://github.com/FooterManDev/Tritium-Companion.git"
            developerConnection = "scm:git:ssh://git@github.com/FooterManDev/Tritium-Companion.git"
        }
    }

    repositories {
        maven {
            name = "GH"
            url = uri("https://maven.pkg.github.com/FooterManDev/Tritium-Companion")
            credentials {
                username = findProperty("gpr.user") as String
                password = findProperty("gpr.key")  as String
            }
        }
    }
}

tasks.matching { it.name == "generateMetadataFileForMavenPublication" }.configureEach {
    dependsOn(tasks.matching { it.name == "plainJavadocJar" || it.name == "javadocJar" })
}

afterEvaluate {
    publishing.publications.withType(MavenPublication::class.java).forEach { pub ->
        println("Publication '${pub.name}':")
        pub.artifacts.forEach { a ->
            println("  - extension='${a.extension}', classifier='${a.classifier}', file='${a.file?.name}'")
        }
    }
}
