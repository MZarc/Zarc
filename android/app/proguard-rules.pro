# The JNI entry points are looked up by name, so they must be kept.
-keep class com.zarc.vault.core.ZarcNative { *; }
-keep class com.zarc.vault.core.ZarcException { *; }

# Keep the native library name.
-keepclasseswithmembernames class * {
    native <methods>;
}

# The instrumented test APK is built with the same build type as the app under
# test, so it inherits R8. The androidx.test libraries reference ErrorProne
# annotations that are compile-time only and are not shipped.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# The Kotlin runtime marker interfaces have no members, so R8 removes them when
# the application itself never references them. Any component that is loaded
# into the application process afterwards (the instrumentation, a crash
# reporter, a dynamically loaded library) then fails with
# `NoClassDefFoundError: kotlin.jvm.internal.Lambda`.
#
# This was found by running the instrumented tests against the *release* build
# on a physical device: the release artifact crashed while the debug build
# worked.
-keep class kotlin.jvm.internal.Lambda { *; }
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.** { volatile <fields>; }
-dontwarn kotlin.**

# The classes that the JNI layer and the instrumented tests address by name
# keep their original names. The JNI entry points are resolved by name at
# runtime, and the instrumented test package calls the platform adapter
# directly, so an obfuscated name would break both.
-keep class com.zarc.vault.core.ZarcNative { *; }
-keep class com.zarc.vault.core.ZarcException { *; }
-keep class com.zarc.vault.core.VaultRepository { *; }
-keep class com.zarc.vault.keystore.** { *; }

# The instrumented tests run inside the application process and need the
# tracing classes of the test framework.
-keep class androidx.tracing.** { *; }

# The instrumented test classes keep their names, so that a minified test
# package can still find the `@Before` and `@Test` methods.
-keep class *InstrumentedTest { *; }
-keep class *ProbeTest { *; }
-keep @org.junit.Test class * { *; }
-keepclassmembers class * { @org.junit.Test <methods>; }
-keepclassmembers class * { @org.junit.Before <methods>; }
