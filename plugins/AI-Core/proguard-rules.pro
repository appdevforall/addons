# AI Core Plugin ProGuard Rules

# Keep plugin entry point
-keep public class com.itsaky.androidide.plugins.aicore.plugin.AiCorePlugin {
    public <methods>;
}

# Keep LlmInferenceService implementation
-keep public class com.itsaky.androidide.plugins.aicore.services.LlmInferenceServiceImpl {
    public <methods>;
}

# Keep the settings fragment: it is instantiated by name from the fragmentClassName this plugin
# hands the host for its Preferences entry.
-keep public class com.itsaky.androidide.plugins.aicore.fragments.AiSettingsFragment {
    public <init>(...);
}

# Keep plugin-api interfaces
-keep interface com.itsaky.androidide.plugins.** { *; }
