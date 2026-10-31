plugins {
  id("loupe.kmp.library")
  id("loupe.kmp.serialization")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.settings.api)
      implementation(projects.sessions.api)
      implementation(projects.platform.architectureCore)
      implementation(projects.platform.preferencesCore)
      implementation(projects.platform.powerCore)
    }
    commonTest.dependencies {
      implementation(projects.sessions.fake)
      implementation(projects.platform.preferencesTest)
      implementation(projects.platform.architectureTest)
      implementation(libs.turbine)
      implementation(projects.settings.fake)
    }
  }
}
