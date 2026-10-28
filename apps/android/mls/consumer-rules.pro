# R8 rules every consumer of :mls inherits. JNA looks up native symbols, fields and callbacks by
# name, and the generated UniFFI bindings (mlsffi.kt) rely on that, so none of it may be renamed or removed.
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.example.app.mls.ffi.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { <fields>; }
-dontwarn java.awt.**
