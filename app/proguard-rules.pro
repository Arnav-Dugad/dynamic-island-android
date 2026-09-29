# Island has no reflection-based serialization; the defaults from proguard-android-optimize.txt
# are sufficient. Keep manifest-declared components (AGP does this automatically).

# Keep line numbers so crash traces from personal release builds stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
