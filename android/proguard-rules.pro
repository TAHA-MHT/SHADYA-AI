-keep class com.sun.jna.* { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }

# tflite_flutter référence des classes optionnelles pour l'accélération GPU
# qu'on n'utilise pas dans l'app. On dit à R8 de les ignorer plutôt que de
# faire échouer le build parce qu'elles sont introuvables.
-dontwarn org.tensorflow.lite.gpu.**
-keep class org.tensorflow.lite.gpu.** { *; }
