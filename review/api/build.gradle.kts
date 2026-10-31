plugins {
  id("loupe.kmp.library")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.domainEntities)
      api(projects.platform.core)
    }
  }
}
