# Kept through trimming (docs/anthemos-2.md, "Builds"): code reached in ways
# R8 can't follow. AndroidX, Hilt, Room, WorkManager, OkHttp, Retrofit and
# kotlinx.serialization bring their own rules.

# Crash reports still name the file and line.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Vosk (speech) reaches its native library through JNA, which finds Java
# classes and methods by name from native code.
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**

# SQLCipher (the encrypted store) calls back into Java from native code.
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# Gson reads and writes the Direct API's models by their field names.
-keep class com.domain.app.core.direct.api.** { *; }
-keepattributes Signature, *Annotation*

# EncryptedSharedPreferences (Tink) references classes it doesn't ship.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
