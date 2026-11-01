plugins {
  id("lemonadecode.kmp.library")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(libs.kotlinx.serialization.json)
      implementation(projects.platform.architectureCore)
    }
  }
}
