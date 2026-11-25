plugins {
  id("lemonadecode.kmp.library")
  id("lemonadecode.kmp.serialization")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.imports.api)
      implementation(projects.sessions.api)
      implementation(projects.platform.architectureCore)
      implementation(projects.platform.processCore)
      implementation(projects.platform.preferencesCore)
    }
    commonTest.dependencies {
      implementation(projects.sessions.fake)
      implementation(projects.platform.architectureTest)
      implementation(projects.platform.preferencesTest)
    }
  }
}
