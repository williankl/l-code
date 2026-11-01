/** Adds kotlinx.serialization: the compiler plugin and the JSON runtime. */
plugins {
  id("org.jetbrains.kotlin.plugin.serialization")
}

val libs = versionCatalogs.named("libs")

pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
  extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
    sourceSets.getByName("commonMain").dependencies {
      implementation(libs.findLibrary("kotlinx-serialization-json").get())
    }
  }
}
