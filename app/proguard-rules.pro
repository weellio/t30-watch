# Release builds have minify disabled (see app/build.gradle.kts).
# If R8 is ever turned on, start with these and test every SDK feature.
-keep class com.veepoo.** { *; }
-keep class com.inuker.bluetooth.** { *; }
-keep class com.jieli.** { *; }
-keep class vip.inode.** { *; }
-dontwarn com.veepoo.**
-dontwarn com.jieli.**
