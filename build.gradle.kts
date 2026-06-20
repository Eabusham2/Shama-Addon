plugins {
    alias(libs.plugins.loom)
}

base {
    archivesName.set(property("archives_base_name") as String)
}

version = property("mod_version") as String
group = property("maven_group") as String

configurations.all {
    // Meteor's 1.21.11-SNAPSHOT is a changing module; check more often than
    // Gradle's default 24h so a stale cache can't cause resolution failures.
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.meteordev.org/releases")
    maven("https://maven.meteordev.org/snapshots")
}

dependencies {
    minecraft(libs.minecraft)
    mappings(libs.yarn)
    modImplementation(libs.fabric.loader)
    modImplementation(libs.fabric.api)

    // Meteor Client — pinned to the MC-matched snapshot via the version catalog
    modImplementation(libs.meteor.client)

    // Log4j2 core is bundled with Minecraft at runtime; needed at compile time
    // for the latest.log filter. compileOnly so we don't ship a duplicate.
    compileOnly("org.apache.logging.log4j:log4j-core:2.24.3")
}

tasks.processResources {
    val propertyMap = mapOf(
        "version" to project.version,
        "mc_version" to libs.versions.minecraft.get()
    )
    inputs.properties(propertyMap)
    filesMatching("fabric.mod.json") {
        expand(propertyMap)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
