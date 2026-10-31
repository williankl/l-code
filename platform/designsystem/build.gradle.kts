plugins {
  id("loupe.kmp.feature")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(libs.lemonade.ui)
    }
  }
}
