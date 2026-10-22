# Rules for the instrumented test APK.
#
# The test APK is built with the same build type as the app under test, so
# it inherits R8. The androidx.test libraries reference ErrorProne
# annotations that are compile-time only and not shipped.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.junit.**
-keep class com.zarc.vault.** { *; }

# The test framework is loaded into the application process, and R8 would
# remove the parts of it that the (minified) test package does not reference
# directly - `androidx.tracing.Trace` for example. The framework is therefore
# kept as it is.
-keep class androidx.test.** { *; }
-keep class androidx.tracing.** { *; }
-keep class androidx.annotation.** { *; }
-keep class androidx.core.** { *; }
-keep class org.junit.** { *; }
-keep class junit.** { *; }
-keep class org.hamcrest.** { *; }
-keep class com.google.errorprone.annotations.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisible*
