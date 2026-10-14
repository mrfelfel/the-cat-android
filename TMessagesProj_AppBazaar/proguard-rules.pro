# Cafe Bazaar in-app billing (Poolakey). Poolakey talks to the Bazaar app over
# AIDL and reflects into its own model classes, so keep them intact under R8.
-keep class ir.cafebazaar.poolakey.** { *; }
-keep class com.android.vending.billing.** { *; }
-dontwarn ir.cafebazaar.poolakey.**
