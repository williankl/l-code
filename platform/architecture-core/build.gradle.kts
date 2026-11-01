plugins {
  id("lemonadecode.kmp.library")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.platform.diCore)
    }
    getByName("desktopMain").dependencies {
      implementation(libs.kotlinx.coroutines.swing)
    }
  }
}
