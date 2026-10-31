plugins {
  id("loupe.kmp.library")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.data.git.api)
      implementation(projects.platform.architectureCore)
      implementation(projects.platform.processCore)
    }
  }
}

kotlin {
  sourceSets {
    getByName("desktopTest").dependencies {
      implementation(projects.platform.architectureTest)
    }
  }
}
