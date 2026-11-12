# Cafe Bazaar in-app billing (Poolakey). Poolakey talks to the Bazaar app over
# AIDL and reflects into its own model classes, so keep them intact under R8.
-keep class ir.cafebazaar.poolakey.** { *; }
-keep class com.android.vending.billing.** { *; }
-dontwarn ir.cafebazaar.poolakey.**

# The store bridge lives in the shared :TMessagesProj library. R8 has broken
# every field-based registration of it in the minified bazaar build — stripping
# a register() accessor (NoSuchMethodError at startup), then constant-folding
# a static field to null and later an ApplicationLoader instance field the same
# way (isAvailable() became a hardcoded false and every store purchase silently
# fell through to the invoice WebView). The bridge is now a lazily-created
# virtual override on the Application (getStoreBillingBridge) — the same shape
# as isBazaarBuild(), which ships correctly. Keep that chain explicitly; it
# gates real payment correctness and has burned us three times.
-keep class org.telegram.messenger.billing.** { *; }
-keepclassmembers class org.telegram.messenger.ApplicationLoader {
    public org.telegram.messenger.billing.StoreBillingBridge getStoreBillingBridge();
}
-keepclassmembers class * extends org.telegram.messenger.ApplicationLoader {
    public org.telegram.messenger.billing.StoreBillingBridge getStoreBillingBridge();
}

# The Bazaar receipt RPC needs no rule HERE -- it is defined in the :TMessagesProj
# library, and only the library's own R8 pass can strip it, so the keep lives in
# TMessagesProj/proguard-rules.pro. There used to be a `-dontwarn` for it in this
# file claiming R8 false-positived; it did not. R8 was reporting the class as
# genuinely missing from the library's output, and suppressing the warning let
# the Bazaar build ship and crash with NoClassDefFoundError on the first receipt.
# Do not reintroduce it.
