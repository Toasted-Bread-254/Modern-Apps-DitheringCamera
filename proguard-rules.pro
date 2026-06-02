-dontwarn com.google.re2j.**
-dontwarn java.beans.**
-dontobfuscate

# JavaMail / Jakarta Mail — providers are loaded via reflection and META-INF/javamail.providers
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class com.sun.activation.** { *; }
-keep class javax.activation.** { *; }
-keep class jakarta.mail.** { *; }
-keep class jakarta.activation.** { *; }

# Tesseract
-keep class com.googlecode.tesseract.android.** { *; }

# LiteRT LM / Gemma 4
-keep class com.google.ai.edge.litertlm.** { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}