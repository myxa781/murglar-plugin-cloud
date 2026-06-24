plugins {
    alias(catalog.plugins.murglar.plugin.core)
}

murglarPlugin {
    id = "cloud"
    name = "Cloud Music"
    version = catalog.versions.murglar.cloud.map(String::toInt)
    entryPointClass = "com.badmanners.murglar.lib.cloud.CloudMurglar"
}
