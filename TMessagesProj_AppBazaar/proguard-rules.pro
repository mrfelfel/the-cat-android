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

# R8's "missing classes" check false-positives on this TL schema class: it IS
# compiled into the :TMessagesProj library (TLRPC$TL_payments_assignBazaarTransaction
# is present in the release output) and is referenced directly by
# BazaarReceiptQueue / BazaarBillingBridge, so it is kept and shipped. Only the
# standalone flavour never references it (nothing there uses the Bazaar receipt
# path), which is why this surfaces only here. R8 itself generated this rule in
# build/outputs/mapping/.../missing_rules.txt.
-dontwarn org.telegram.tgnet.TLRPC$TL_payments_assignBazaarTransaction
