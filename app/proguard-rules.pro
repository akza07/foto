# LiteRT-LM reaches Kotlin classes from native code and parses model metadata
# with Gson. Both are name-sensitive, so keep the SDK intact rather than risk a
# release-only crash that debug builds cannot reproduce.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** { *; }

# AppSearch's local backend is reached through generated factories and protobuf
# descriptors; its own consumer rules handle the @Document path, but the
# embedding/schema classes are referenced reflectively by the storage layer.
-keep class androidx.appsearch.localstorage.** { *; }
