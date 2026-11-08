# OkHttp ships its own consumer rules. Nothing in PULSE uses reflection.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
# Strip debug logging calls from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
