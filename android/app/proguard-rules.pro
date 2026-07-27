-keep class com.sun.jna.* { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }

# tflite_flutter référence des classes optionnelles pour l'accélération GPU
# qu'on n'utilise pas dans l'app.
-dontwarn org.tensorflow.lite.gpu.**
-keep class org.tensorflow.lite.gpu.** { *; }

# JNA (utilisé par vosk_flutter_2) référence des classes AWT (interface
# graphique de bureau Java) pour un usage optionnel sur ordinateur.
# Ces classes n'existent pas sur Android et ne sont jamais utilisées ici.
-dontwarn java.awt.**
