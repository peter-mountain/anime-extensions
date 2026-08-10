plugins {
    alias(kei.plugins.library)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":lib:unpacker"))
}
