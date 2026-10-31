plugins {
  id("loupe.kmp.compose")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(libs.compose.components.resources)
    }
  }
}
