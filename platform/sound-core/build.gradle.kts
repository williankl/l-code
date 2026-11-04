plugins {
  id("lemonadecode.kmp.library")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      implementation(projects.platform.diCore)
    }
  }
}
