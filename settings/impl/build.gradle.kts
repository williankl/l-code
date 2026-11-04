plugins {
  id("lemonadecode.kmp.library")
  id("lemonadecode.kmp.serialization")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.settings.api)
      implementation(projects.sessions.api)
      implementation(projects.platform.architectureCore)
      implementation(projects.platform.preferencesCore)
      implementation(projects.platform.powerCore)
      implementation(projects.platform.soundCore)
    }
    commonTest.dependencies {
      implementation(projects.sessions.fake)
      implementation(projects.platform.preferencesTest)
      implementation(projects.platform.architectureTest)
      implementation(projects.platform.soundTest)
      implementation(libs.turbine)
      implementation(projects.settings.fake)
    }
  }
}
