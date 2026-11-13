# KaivoFlow R8 / ProGuard rules
#
# Everything here exists because a release build failed without it, or because reflection is
# genuinely used at runtime. Nothing is a blanket keep — blanket keeps are what makes 40 MB APKs.

# ---------------------------------------------------------------- Kotlin / coroutines
-keepclassmembers class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# kotlinx.serialization — generated serializers are looked up reflectively by name.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    static *** serializer(...);
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keep class <1>$$serializer { *; }
-if @kotlinx.serialization.Serializable class **
-keep, allowobfuscation class <1> { *; }

# ---------------------------------------------------------------- Hilt / Dagger
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.internal.GeneratedComponent { *; }
-keepclassmembers class * { @javax.inject.Inject <init>(...); }
-keepclassmembers class * { @javax.inject.Inject <fields>; }
-keep, allowobfuscation @interface dagger.hilt.android.lifecycle.HiltViewModel
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# ---------------------------------------------------------------- Room
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------- quickjs-kt (JNI)
# Native methods are resolved by name from C: obfuscating them breaks the Code Step at runtime.
-keepclasseswithmembernames class * { native <methods>; }
-keep class app.cash.quickjs.** { *; }
-keep interface app.cash.quickjs.** { *; }
-keepclassmembers class app.cash.quickjs.** { *** _evaluate(...); *** _compile(...); *** _execute(...); *** _close(...); *** _memoryUsage(...); }

# ---------------------------------------------------------------- Ktor / OkHttp
-dontwarn org.slf4j.**
-dontwarn kotlinx.serialization.**
-keep class io.ktor.client.engine.okhttp.** { *; }
-keepclassmembers class io.ktor.** { volatile <fields>; }
-dontwarn io.ktor.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------- Engine step registry
# Step ids and param keys are data, not class names, but the executor registry is looked up by
# string id at Run time via NodeCatalog — keep the concrete node classes that implement it.
-keep class com.kaivoflow.core.nodes.** implements com.kaivoflow.core.engine.StepExecutor { *; }

# ---------------------------------------------------------------- Compose
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**

# ---------------------------------------------------------------- Misc
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
# Keep enum values used by name in persisted JSON.
-keepclassmembers enum * { *; }
