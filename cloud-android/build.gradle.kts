plugins {
    alias(catalog.plugins.murglar.plugin.android)
}

murglarAndroidPlugin {
    id = "cloud"
    name = "Cloud Music"
    version = catalog.versions.murglar.cloud.map(String::toInt)
    entryPointClass = "com.badmanners.murglar.lib.cloud.CloudMurglar"
}

dependencies {
    implementation(project(":cloud-core"))
}

android {
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
