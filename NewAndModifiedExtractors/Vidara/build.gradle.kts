plugins {
    alias(kei.plugins.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":lib:playlistutils"))
}
