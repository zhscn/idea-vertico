plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.github.zhscn"
version = "0.1.0"

val ideHome = providers.gradleProperty("ideHome")
    .orElse(System.getProperty("user.home") + "/.local/share/JetBrains/Toolbox/apps/intellij-idea")

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        // CI has no installed IDE, so it passes -PideVersion and downloads the platform.
        val ideVersion = providers.gradleProperty("ideVersion")
        if (ideVersion.isPresent) intellijIdea(ideVersion.get()) else local(ideHome)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
}
