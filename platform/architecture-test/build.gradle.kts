plugins {
  id("loupe.kmp.library")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.platform.architectureCore)
      api(libs.kotlinx.coroutines.test)
    }
  }
}
