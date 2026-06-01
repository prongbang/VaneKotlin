-keep class com.inteniquetic.vanekotlin.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * {
    native <methods>;
}
-keepattributes *Annotation*,InnerClasses,EnclosingMethod
-dontwarn java.awt.**
