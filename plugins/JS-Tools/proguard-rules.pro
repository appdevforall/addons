# Plugin classes are instantiated and called by the IDE via reflection.
-keep class com.appdevforall.js.plugin.** { *; }

# Keep the plugin API surface the IDE binds against.
-keep class com.itsaky.androidide.plugins.** { *; }
