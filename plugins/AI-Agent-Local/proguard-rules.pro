# AI Agent Local Plugin ProGuard Rules

# Keep plugin entry point
-keep public class com.itsaky.androidide.plugins.aiagentlocal.plugin.LocalLlmPlugin {
    public <methods>;
}

# Keep the settings fragment: it is instantiated by name from getSettingsFragmentClassName().
-keep public class com.itsaky.androidide.plugins.aiagentlocal.settings.LocalLlmSettingsFragment {
    public <init>(...);
}

# Keep plugin-api interfaces
-keep interface com.itsaky.androidide.plugins.** { *; }

# Keep the JNI surface of the prebuilt llama.cpp library
-keep class android.llama.cpp.** { *; }
-keep class com.itsaky.androidide.llamacpp.** { *; }
