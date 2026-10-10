# ProGuard / R8 rules

# --- Kotlin reflect / coroutines ---
-keepclassmembers class kotlin.Metadata { *; }
-keep class kotlin.coroutines.Continuation { *; }
-dontwarn kotlinx.coroutines.**

# --- Room ---
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# --- Compose ---
-dontwarn androidx.compose.**

# --- jsoup ---
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# --- PDFBox-Android (TomRoush fork) ---
-keep class com.tom_roush.pdfbox.** { *; }
-keep class org.apache.pdfbox.** { *; }
-dontwarn org.apache.pdfbox.**
-dontwarn com.tom_roush.**
-keep class com.tom_roush.pdfbox.pdmodel.PDDocument { *; }
# Keep the native loader
-keep class com.tom_roush.pdfbox.android.PDFBoxResourceLoader { *; }

# --- Media3 ---
-dontwarn androidx.media3.**

# --- DataStore ---
-dontwarn androidx.datastore.**

# --- Kotlin metadata ---
-keep class kotlin.coroutines.EmptyCoroutineContext { *; }
