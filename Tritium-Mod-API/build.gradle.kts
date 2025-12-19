plugins {
    `java-library`
    `maven-publish`
    signing
}

group = "io.github.footermandev"
version = "0.1.0"

java { withJavadocJar(); withSourcesJar() }

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation("com.google.code.gson:gson:2.13.2")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("Tritium Mod API")
                description.set("API for Tritium Launcher integration")
                url.set("https://github.com/FooterManDev/Tritium-Companion")
                licenses { licenses { name.set("MIT"); url.set("https://opensource.org/licenses/MIT") } }
                developers { developer { name.set("FooterManDev") } }
                scm { connection.set("scm:git:git://github.com/FooterManDev/Tritium-Companion.git") }
            }
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

