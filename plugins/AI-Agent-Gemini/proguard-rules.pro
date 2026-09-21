# AI Agent Gemini Plugin ProGuard Rules

# Keep plugin entry point
-keep public class com.itsaky.androidide.plugins.aiagentgemini.plugin.GeminiPlugin {
    public <methods>;
}

# Keep the backend: AI Core's settings pane calls listModels across the plugin
# classloader boundary, because listModels is not on LlmBackend. Renaming or
# stripping it breaks the model picker and key verification silently.
-keep public class com.itsaky.androidide.plugins.aiagentgemini.backend.GeminiBackend {
    public <methods>;
}

# Keep the settings fragment: it is instantiated by name from getSettingsFragmentClassName().
-keep public class com.itsaky.androidide.plugins.aiagentgemini.settings.GeminiSettingsFragment {
    public <init>(...);
}

# Keep plugin-api interfaces
-keep interface com.itsaky.androidide.plugins.** { *; }
