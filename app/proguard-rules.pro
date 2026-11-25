# LiteRT-LM (the on-device reader) ships no consumer rules: its JNI layer calls back into Kotlin
# classes by name and it serializes messages with Gson, so keep the API package whole.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# ML Kit registers its components through firebase-components, which instantiates each
# ComponentRegistrar named in the manifest by reflection through its no-argument constructor.
# Without this, R8 strips the constructors and text recognition and subject segmentation never
# register ("Could not instantiate ...Registrar" at startup).
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
