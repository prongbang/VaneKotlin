-keep class com.inteniquetic.vanekotlin.** { *; }
-keep class com.sun.jna.** { *; }
# Reached only from Rust over JNI, so R8 cannot see the usage and would strip
# it — taking TCP-transport certificate verification with it.
-keep, includedescriptorclasses class org.rustls.platformverifier.** { *; }
-keepclassmembers class * {
    native <methods>;
}
-keepattributes *Annotation*,InnerClasses,EnclosingMethod
-dontwarn java.awt.**
