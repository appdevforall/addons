# AI Agent Claude Plugin ProGuard Rules

# Keep plugin entry point
-keep public class com.itsaky.androidide.plugins.aiagentclaude.plugin.ClaudePlugin {
    public <methods>;
}

# Keep the backend: its settings pane resolves it through ClaudePlugin.getBackend() to list
# models and test a connection, and AI Core reaches it across the plugin classloader boundary.
-keep public class com.itsaky.androidide.plugins.aiagentclaude.backend.ClaudeBackend {
    public <methods>;
}

# Keep the settings pane: it is named to the host as a string by
# ClaudeBackend.getSettingsFragmentClassName() and instantiated reflectively.
-keep public class com.itsaky.androidide.plugins.aiagentclaude.settings.ClaudeSettingsFragment {
    public <methods>;
}

# Keep plugin-api interfaces
-keep interface com.itsaky.androidide.plugins.** { *; }
