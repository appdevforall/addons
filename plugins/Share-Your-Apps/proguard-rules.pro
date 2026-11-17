# Rules for R8, which would shrink the plugin's release build. R8 is off (isMinifyEnabled = false, in
# build.gradle.kts), and AndroidX is compileOnly; these rules record what R8 needs here, if it's ever turned back on.

# Keep every class of the plugin's own, with all its members, unchanged: Code On The Go loads the plugin by its
# class names, and Pebble reads the objects passed to templates by their property names. The plugin's own code is
# small, so this costs little; R8 still removes the unused parts of AndroidX and ZXing.
-keep class org.appdevforall.shareyourapps.** { *; }

# No renaming, so error reports from testers keep readable names.
-dontobfuscate

# Code On The Go loads classes parent-first: when the plugin uses an AndroidX class that Code On The Go also has,
# Code On The Go's copy runs, not the plugin's (a NoSuchMethodError showed this, for ActivityResultLauncher). So R8
# may remove unused library code, but must never change what's left: no optimising — R8 had specialised
# launch(Object) to launch(Intent) in the bundled copy, which Code On The Go's copy doesn't have — and every
# library class the plugin uses keeps its exact methods and signatures.
-dontoptimize
-keep,allowshrinking class androidx.** { *; }
-keep,allowshrinking class com.google.zxing.** { *; }

# Code On The Go, not the plugin, calls the plugin's callbacks — the result of an activity, for one — so R8 saw no
# caller, removed ActivityResultCallback.onActivityResult, and with it the bodies of both of the plugin's result
# callbacks: an AbstractMethodError, when the server answered. Keep every method of the AndroidX interfaces and the
# abstract methods of the AndroidX classes that remain, so whatever the plugin implements keeps its code.
-keepclassmembers interface androidx.** { <methods>; }
-keepclassmembers abstract class androidx.** { abstract <methods>; }

# These come from Code On The Go at run time, not from the plugin, so R8 won't find them in it.
-dontwarn io.pebbletemplates.**
-dontwarn com.itsaky.androidide.**
-dontwarn org.slf4j.**
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn org.jetbrains.annotations.**
