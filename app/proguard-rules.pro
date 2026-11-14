# ONNX Runtime is called from native code via JNI; keep its Java API intact.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
