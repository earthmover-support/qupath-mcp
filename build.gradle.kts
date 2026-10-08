plugins {
    id("com.gradleup.shadow") version "9.3.0"
    id("qupath-conventions")
}

qupathExtension {
    name = "qupath-gui-driver"
    group = "io.earthmover.qupath"
    version = "0.1.4"
    description = "Serves MCP from inside QuPath so an AI agent can drive the GUI"
    automaticModule = "io.earthmover.qupath.driver"
}

dependencies {
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)
    shadow(libs.groovy.core)

    implementation("io.modelcontextprotocol.sdk:mcp-core:1.1.2")
    implementation("io.modelcontextprotocol.sdk:mcp-json-jackson3:1.1.2")
    implementation("org.eclipse.jetty:jetty-server:11.0.20")
    implementation("org.eclipse.jetty:jetty-servlet:11.0.20")
}

tasks.shadowJar {
    mergeServiceFiles()
    // Relocated so they cannot collide with the copies other extensions or QuPath itself bundle.
    exclude("org/slf4j/**")
    for (pkg in listOf("org.eclipse.jetty", "jakarta.servlet", "reactor", "org.reactivestreams", "tools.jackson",
            "com.fasterxml.jackson", "com.networknt", "com.ethlo", "org.snakeyaml", "io.micrometer"))
        relocate(pkg, "io.earthmover.qupath.driver.shaded.$pkg")
}
