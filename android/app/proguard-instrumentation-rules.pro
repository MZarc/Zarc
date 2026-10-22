# Rules for a release build that is instrumented on a device.
#
# The instrumented tests run *inside* the application process, and the test
# framework is written in Kotlin. R8 would shrink the Kotlin runtime down to
# the classes the application itself uses, which makes the framework fail
# with `NoClassDefFoundError`. This variant keeps the runtime intact so the
# shipping code path can be exercised on real hardware.
#
# The build is selected with `-Pzarc.testBuildType=release`; the ordinary
# release artifact does not include these rules and stays lean.
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.junit.**
