# WAM release keep rules.
# androidx libraries ship their own consumer ProGuard rules (appcompat, material, webkit).
# WAM uses no reflection, no JNI and no serialization frameworks, so very few rules are needed.

# Keep line numbers for readable crash traces in release builds.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
