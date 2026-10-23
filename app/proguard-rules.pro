# Release: minify disattivato (vedi app/build.gradle.kts), quindi queste regole non sono usate.
# Se in futuro si attiva R8, Ink usa JNI: tenere le classi native.
-keep class androidx.ink.** { *; }
