# No reflection-based JSON or DI in this app; defaults from proguard-android-optimize are enough.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
# Strip verbose logging in release builds. Stream URLs contain credentials and must never be logged.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
