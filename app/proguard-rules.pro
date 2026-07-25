# Compose optimizations
-keep class androidx.compose.runtime.snapshots.SnapshotStateList { *; }
-keepclassmembers class androidx.compose.runtime.snapshots.SnapshotStateList {
    boolean conditionalUpdate(...);
    java.lang.Object mutate(...);
    void update(...);
}

# Keep libbox native methods
-keep class libbox.** { *; }
-keep class go.** { *; }
