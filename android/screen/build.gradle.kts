plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit4)
}

// Screens captured from tmux, which the iOS app's tests read too.
sourceSets.test { resources.srcDir(rootDir.resolve("../testdata/screens")) }
