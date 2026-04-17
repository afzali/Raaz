# JNA (required by lazysodium)
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }

# SQLCipher
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }

# Lazysodium
-keep class com.goterl.lazysodium.** { *; }

# Retrofit + Gson models
-keepattributes Signature
-keepattributes *Annotation*
-keepclassmembers class io.raaz.messenger.data.network.** { *; }
-keep class io.raaz.messenger.data.network.** { *; }

# ZXing
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.** { *; }
-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window

# Navigation
-keepnames class androidx.navigation.fragment.NavHostFragment
