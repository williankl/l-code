plugins {
  id("lemonadecode.kmp.library")
  id("lemonadecode.kmp.serialization")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.sessions.api)
      implementation(projects.data.git.api)
      implementation(projects.platform.architectureCore)
      implementation(projects.platform.processCore)
      implementation(projects.platform.preferencesCore)
    }
    commonTest.dependencies {
      implementation(projects.data.git.fake)
      implementation(projects.platform.architectureTest)
    }
    getByName("desktopTest").dependencies {
      implementation(projects.data.git.fake)
      implementation(projects.platform.architectureTest)
    }
  }
}
