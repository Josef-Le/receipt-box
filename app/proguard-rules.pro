# Play Billing
-keep class com.android.vending.billing.** { *; }
-keep class com.android.billingclient.** { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-dontwarn com.google.mlkit.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep Receipt entity fields
-keepclassmembers class com.receiptbox.app.data.Receipt { *; }

# Tesseract / tess-two JNI
-keep class com.googlecode.tesseract.** { *; }
-keep class com.googlecode.leptonica.** { *; }
-dontwarn com.googlecode.tesseract.**
