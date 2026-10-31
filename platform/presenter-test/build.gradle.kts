plugins {
  id("loupe.kmp.compose")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.platform.presenterCore)
      api(libs.turbine)
      api(libs.kotlinx.coroutines.test)
    }
  }
}
