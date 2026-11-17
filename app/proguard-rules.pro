# Keep Room generated implementations.
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# osmdroid uses reflection for some optional components.
-dontwarn org.osmdroid.**
-keep class org.osmdroid.tileprovider.modules.** { *; }

# Play Services location is optional at runtime.
-dontwarn com.google.android.gms.**
