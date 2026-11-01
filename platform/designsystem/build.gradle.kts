plugins {
  id("lemonadecode.kmp.feature")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(libs.lemonade.ui)
    }
  }
}
