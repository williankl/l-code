plugins {
  id("loupe.kmp.library")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.domainEntities)
      api(projects.platform.core)
    }
    getByName("desktopMain").dependencies {
      api(projects.platform.terminal)
    }
  }
}
