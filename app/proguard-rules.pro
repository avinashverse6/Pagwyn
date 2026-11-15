# Pagwyn ProGuard rules.
#
# Pagwyn has no reflection-based libraries beyond AndroidX, which ships its own
# consumer rules. These rules keep the public app surface stable and remove the
# optional debug/testing artefacts from release builds.

-dontwarn org.jetbrains.annotations.**

# Keep the application entry points referenced from the manifest.
-keep class io.pagwyn.reader.PagwynApplication { *; }

# Kotlin metadata is not required at runtime in release builds.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
