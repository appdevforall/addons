# AI Agent OpenAI Plugin ProGuard Rules

# Keep plugin entry point
-keep public class com.itsaky.androidide.plugins.aiagentopenai.plugin.OpenAiPlugin {
    public <methods>;
}

# Keep the backend: its settings pane resolves it through OpenAiPlugin.getBackend() to list
# models and test a connection, and AI Core reaches it across the plugin classloader boundary.
-keep public class com.itsaky.androidide.plugins.aiagentopenai.backend.OpenAiBackend {
    public <methods>;
}

# Keep the settings pane: it is named to the host as a string by
# OpenAiBackend.getSettingsFragmentClassName() and instantiated reflectively.
-keep public class com.itsaky.androidide.plugins.aiagentopenai.settings.OpenAiSettingsFragment {
    public <methods>;
}

# Keep plugin-api interfaces
-keep interface com.itsaky.androidide.plugins.** { *; }
