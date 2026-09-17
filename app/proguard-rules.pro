# Conscrypt carries a pre-KitKat adapter whose Harmony superclass does not
# exist on this app's minSdk 31 runtime. R8 still scans the dead reference.
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
-dontwarn com.android.org.conscrypt.SSLParametersImpl

# The small SPAKE2 bridge is native and resolves methods by JNI name.
-keep class io.github.muntashirakon.crypto.spake2.** { *; }
-dontwarn io.github.muntashirakon.crypto.spake2.**
