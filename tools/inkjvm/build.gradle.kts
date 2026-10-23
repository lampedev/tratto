// Banco di prova JVM per i pennelli: compila gli stessi sorgenti dell'app (Brushes.kt, Model.kt)
// contro gli artefatti Ink per JVM e li esercita con tratti sintetici.
plugins {
    kotlin("jvm") version "2.4.21"
    application
}
val ink = "1.1.0-alpha09"
dependencies {
    implementation("androidx.ink:ink-brush-jvm:$ink")
    implementation("androidx.ink:ink-strokes-jvm:$ink")
    implementation("androidx.ink:ink-geometry-jvm:$ink")
    implementation("androidx.ink:ink-storage-jvm:$ink")
    implementation("androidx.ink:ink-nativeloader-jvm:$ink")
}
kotlin { jvmToolchain(21) }
sourceSets["main"].kotlin.srcDirs(
    "src/main/kotlin",
    "../../app/src/main/java/com/caro/tratto/ink",
)
sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("model"))
val copyModel by tasks.registering(Copy::class) {
    from("../../app/src/main/java/com/caro/tratto/model/Model.kt")
    from("../../app/src/main/java/com/caro/tratto/editor/SelectionGeometry.kt")
    from("../../app/src/main/java/com/caro/tratto/editor/ShapeRecognizer.kt")
    from("../../app/src/main/java/com/caro/tratto/data/StrokesFile.kt")
    into(layout.buildDirectory.dir("model"))
}
tasks.named("compileKotlin") { dependsOn(copyModel) }
application { mainClass.set("BrushCheckKt") }
