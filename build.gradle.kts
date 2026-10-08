plugins {
    id("com.gradleup.shadow") version "8.3.5"
    id("qupath-conventions")
}

qupathExtension {
    name = "qupath-gui-driver"
    group = "io.earthmover.qupath"
    version = "0.1.0-SNAPSHOT"
    description = "Test-only extension that runs a Groovy script against the QuPath GUI and captures screenshots"
    automaticModule = "io.earthmover.qupath.driver"
}

dependencies {
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)
    shadow(libs.groovy.core)
}
