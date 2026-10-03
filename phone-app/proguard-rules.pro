# Rokid CXR-M ships no consumer rules; its native code calls back into Java by name,
# and it uses Retrofit/Gson reflection internally.
-keep class com.rokid.** { *; }
-dontwarn com.rokid.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
