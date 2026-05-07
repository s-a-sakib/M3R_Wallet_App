# M3R Wallet ProGuard Rules

# BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ZXing
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Wallet data classes (must not be obfuscated for Gson serialization)
-keep class com.m3r.wallet.data.local.WalletStorage$StoredWallet { *; }
-keep class com.m3r.wallet.data.local.WalletStorage$TxRecord { *; }

# Core crypto - never obfuscate
-keep class com.m3r.wallet.core.** { *; }
