# JNA and the UniFFI bindings are reached through reflection and native callbacks.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class io.github.affonsopaulo.stremionow.core.** { *; }
-dontwarn java.awt.**
