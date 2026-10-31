/**
 * A module that renders: a feature's `ui` and `impl` modules and shared UI libraries.
 * Compose UI, Lemonade and the presenter runtime.
 */
plugins {
  id("loupe.kmp.compose")
}

val libs = versionCatalogs.named("libs")

kotlin {
  compilerOptions {
    optIn.addAll(
      "com.teya.lemonade.ExperimentalLemonadeApi",
      "androidx.compose.foundation.layout.ExperimentalLayoutApi",
      "androidx.compose.foundation.ExperimentalFoundationApi",
    )
  }

  sourceSets {
    commonMain.dependencies {
      implementation(libs.findLibrary("compose-foundation").get())
      implementation(libs.findLibrary("compose-ui").get())
      implementation(libs.findLibrary("compose-ui-tooling-preview").get())
      implementation(libs.findLibrary("lemonade-ui").get())
    }
  }
}
