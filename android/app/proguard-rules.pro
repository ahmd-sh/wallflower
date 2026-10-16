# JSch loads its algorithms by class name from its config.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
