/**
 * A library that compiles `@Composable` code but renders nothing: presenters' building blocks,
 * UI models. Adds the Compose compiler and runtime, plus Turbine for tests.
 */
plugins {
  id("loupe.kmp.library")
  id("org.jetbrains.compose")
  id("org.jetbrains.kotlin.plugin.compose")
}

val libs = versionCatalogs.named("libs")

kotlin {
  sourceSets {
    commonMain.dependencies {
      implementation(libs.findLibrary("compose-runtime").get())
    }
    commonTest.dependencies {
      implementation(libs.findLibrary("turbine").get())
    }
  }
}
