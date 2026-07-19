package com.redxbot.builder

/**
 * Holds AI-generated source files and metadata for a custom app feature set.
 * Produced by ApiClient.generateAppCode() and consumed by AppBuilderService.
 */
data class AppCodeOutput(
    /** Extra Kotlin and XML files to push on top of the base template. */
    val files: List<GeneratedFile> = emptyList(),
    /** Android permission strings to inject into AndroidManifest.xml. */
    val permissions: List<String> = emptyList(),
    /** Gradle implementation lines to inject into app/build.gradle. */
    val gradleDependencies: List<String> = emptyList(),
    /** <activity .../> XML snippets to inject into AndroidManifest.xml. */
    val manifestActivities: List<String> = emptyList(),
    /** Toolbar menu items to expose in the generated app's MainActivity. */
    val menuItems: List<GeneratedMenuItem> = emptyList()
)

data class GeneratedFile(val path: String, val content: String)

data class GeneratedMenuItem(val title: String, val activityClass: String)
