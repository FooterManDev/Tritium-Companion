plugins {
    `java-library`
    signing
    id("com.vanniktech.maven.publish") version "0.35.0"
}

group = "io.github.tritium_launcher"
val artifactId = "tritium-mod-api"
version = "0.1.2"

java {
    withSourcesJar()
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

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
        url = "https://github.com/Tritium-Launcher/Tritium-Companion"
        licenses {
            license {
                name = "MIT"
                url = "https://opensource.org/licenses/MIT"
            }
        }
        developers {
            developer {
                id = "tritium_launcher"
                name = "FooterManDev"
                url = "https://github.com/FooterManDev"
            }
        }
        scm {
            url = "https://github.com/Tritium-Launcher/Tritium-Companion"
            connection = "scm:git:git://github.com/Tritium-Launcher/Tritium-Companion.git"
            developerConnection = "scm:git:ssh://git@github.com/Tritium-Launcher/Tritium-Companion.git"
        }
    }

}

val gprUser: String? by project
val gprKey: String? by project

publishing {
    repositories {
        if (gprUser != null && gprKey != null) {
            maven {
                name = "GH"
                url = uri("https://maven.pkg.github.com/Tritium-Launcher/Tritium-Companion")
                credentials {
                    username = gprUser
                    password = gprKey
                }
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
