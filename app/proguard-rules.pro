# R8 keep rules for HomeAttach.
#
# The app is small; the only real risk from shrinking is that JSch and BouncyCastle resolve their
# crypto implementations by reflection from string class names, which R8 cannot see. Stripping any
# of those turns ed25519 auth into a silent "Auth fail for methods 'publickey'" with no stack
# trace — exactly the failure ensureEd25519Support() exists to prevent. So the reflective packages
# are kept whole, and the shrink is left to prune the app's own dead code, Compose, and androidx.

# --- JSch (com.github.mwiede fork) ---------------------------------------------------------------
# JSch.setConfig(...) names impl classes as strings ("com.jcraft.jsch.bc.SignatureEd25519", ...)
# and Class.forName's algorithm classes internally. None are referenced by symbol, so all are
# invisible to R8. Keep the package whole; from these kept bridges R8 can still trace into the
# specific BouncyCastle primitives actually used and drop the rest of BC.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# --- BouncyCastle -------------------------------------------------------------------------------
# Provider self-registration and algorithm lookup are reflective. bcprov ships optional deps
# (e.g. its own logging) that are never on the classpath here.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# --- SnakeYAML (QR config payloads) --------------------------------------------------------------
# Binds YAML to constructors/fields reflectively.
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**

# The vendored Termux engine's JNI (JNI.java / libtermux.so) is deliberately not kept: this app
# runs the emulator in remote mode only, no libtermux.so is shipped, and the native path is
# unreachable. Let R8 shrink it like any other dead code.

# --- Tink (backs androidx.security EncryptedSharedPreferences) -----------------------------------
# References compile-time-only annotations (errorprone, javax.annotation) that are never on the
# runtime classpath. Referenced solely in annotation metadata, so warning-only.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
