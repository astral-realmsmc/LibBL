plugins {
    java
    `maven-publish`
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
}

group = "io.rivrs"
version = "1.0-ffb-SNAPSHOT"

repositories {
    // PolarisUtils, published with ./gradlew publishToMavenLocal
    mavenCentral()
    maven {
        name = "papermc-repo"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven { url = uri("https://repo.codemc.io/repository/maven-releases/") }
    maven { url = uri("https://repo.codemc.io/repository/maven-snapshots/") }
    maven("https://maven.astralrealms.fr/repository/maven-public/") {
        name = "astralrealms"
        credentials(PasswordCredentials::class)
    }
}

dependencies {
    paperweight.paperDevBundle("26.3", group = "com.astralrealms.polaris")
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
    compileOnly("com.polaris:PolarisUtils:1.0-SNAPSHOT")

    compileOnly("org.projectlombok:lombok:1.+")
    annotationProcessor("org.projectlombok:lombok:1.+")
}

val targetJavaVersion = 25
java {
    val javaVersion = JavaVersion.toVersion(targetJavaVersion)
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
    if (JavaVersion.current() < javaVersion) {
        toolchain.languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.processResources {
    val projectVersion = project.version.toString()
    inputs.property("version", projectVersion)
    filesMatching(listOf("plugin.yml", "bungee.yml")) {
        filter { line -> line.replace("\${project.version}", projectVersion) }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }

    repositories {
        maven {
            name = "astralrealms"
            url = uri(
                if (version.toString().endsWith("SNAPSHOT"))
                    "https://maven.astralrealms.fr/repository/maven-snapshots/"
                else
                    "https://maven.astralrealms.fr/repository/maven-releases/"
            )
            credentials(PasswordCredentials::class)
        }
    }
}
