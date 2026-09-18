# AI Agent MCP Plugin ProGuard Rules

# Keep plugin entry point
-keep public class com.itsaky.androidide.plugins.aiagentmcp.plugin.McpPlugin {
    public <methods>;
}

# Keep the tool source: AI Core calls it across the plugin classloader boundary.
-keep public class com.itsaky.androidide.plugins.aiagentmcp.tools.McpToolSource {
    public <methods>;
}

# Keep the settings fragment: it is instantiated by name from getSettingsEntries().
-keep public class com.itsaky.androidide.plugins.aiagentmcp.settings.McpSettingsFragment {
    public <init>(...);
}

# Keep plugin-api interfaces
-keep interface com.itsaky.androidide.plugins.** { *; }
