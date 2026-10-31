plugins {
  `kotlin-dsl`
}

group = "dev.loupe.buildlogic"

kotlin {
  jvmToolchain(21)
}

dependencies {
  // Precompiled script plugins need these on the compile classpath to resolve the
  // `kotlin { }`, `compose { }`, `metro { }` and `spotless { }` accessors they configure.
  implementation(libs.kotlin.gradlePlugin)
  implementation(libs.kotlinSerialization.gradlePlugin)
  implementation(libs.composeCompiler.gradlePlugin)
  implementation(libs.composeMultiplatform.gradlePlugin)
  implementation(libs.metro.gradlePlugin)
  implementation(libs.spotless.gradlePlugin)
}
