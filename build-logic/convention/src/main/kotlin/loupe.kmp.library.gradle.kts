/**
 * Plain Kotlin library: domain, data, `api` and `fake` modules. Coroutines in main, kotlin-test
 * and coroutines-test in tests.
 */
plugins {
  id("loupe.kmp.base")
}

val libs = versionCatalogs.named("libs")

kotlin {
  sourceSets {
    matching { it.name.endsWith("Test") }.configureEach {
      languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
    commonMain.dependencies {
      implementation(libs.findLibrary("kotlinx-coroutines-core").get())
    }
    commonTest.dependencies {
      implementation(libs.findLibrary("kotlin-test").get())
      implementation(libs.findLibrary("kotlinx-coroutines-test").get())
    }
  }
}
