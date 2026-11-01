plugins {
  id("lemonadecode.kmp.compose")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(libs.molecule.runtime)
      implementation(libs.kotlinx.coroutines.core)
    }
    getByName("desktopMain").dependencies {
      implementation(libs.kotlinx.coroutines.swing)
    }
  }
}
