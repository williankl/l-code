plugins {
  id("lemonadecode.kmp.library")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.sessions.api)
    }
  }
}
