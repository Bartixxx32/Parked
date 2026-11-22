# Keep Room generated implementations.
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# osmdroid uses reflection for some optional components.
-dontwarn org.osmdroid.**
-keep class org.osmdroid.tileprovider.modules.** { *; }

# zxing-android-embedded bundles a legacy IntentIntegrator for Fragment-based
# integrations. Parked uses ScanContract, so those optional classes are unused.
-dontwarn androidx.fragment.app.**
