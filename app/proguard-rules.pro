# Keep JNI entry points (NodeRuntime) if minification is enabled later.
-keep class com.hmhamster.boardgameweb.NodeRuntime {
    native <methods>;
    private static void onNativeOutput(int, byte[]);
}
