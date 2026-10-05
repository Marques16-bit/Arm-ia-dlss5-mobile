# Mantém os métodos nativos chamados pelo JNI (o C++ procura esses nomes).
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.armia.app.NativeBridge { *; }
