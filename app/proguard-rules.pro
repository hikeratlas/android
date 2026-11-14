# zstd-jni looks its classes up from native code.
-keep class com.github.luben.zstd.** { *; }
# h3-java's native methods are bound by their class and method names.
-keep class com.uber.h3core.** { *; }
