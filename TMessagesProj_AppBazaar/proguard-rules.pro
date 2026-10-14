# Cafe Bazaar in-app billing (Poolakey). Poolakey talks to the Bazaar app over
# AIDL and reflects into its own model classes, so keep them intact under R8.
-keep class ir.cafebazaar.poolakey.** { *; }
-keep class com.android.vending.billing.** { *; }
-dontwarn ir.cafebazaar.poolakey.**

# The store bridge lives in the shared :TMessagesProj library. R8 was inlining
# and stripping StoreBilling.register()/get()/isAvailable() as trivial static
# accessors, which left ApplicationLoaderImpl.onCreate() calling a method that
# no longer existed in the minified dex -> NoSuchMethodError crash on startup
# (java.lang.NoSuchMethodError: No static method register(...) in StoreBilling).
# These gate real payment correctness, so keep the whole bridge intact.
-keep class org.telegram.messenger.billing.** { *; }

# R8's "missing classes" check false-positives on this TL schema class: it IS
# compiled into the :TMessagesProj library (TLRPC$TL_payments_assignBazaarTransaction
# is present in the release output) and is referenced directly by
# BazaarReceiptQueue / BazaarBillingBridge, so it is kept and shipped. Only the
# standalone flavour never references it (nothing there uses the Bazaar receipt
# path), which is why this surfaces only here. R8 itself generated this rule in
# build/outputs/mapping/.../missing_rules.txt.
-dontwarn org.telegram.tgnet.TLRPC$TL_payments_assignBazaarTransaction
