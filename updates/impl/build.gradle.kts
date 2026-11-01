plugins {
  id("lemonadecode.kmp.library")
  id("lemonadecode.kmp.serialization")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.updates.api)
      implementation(projects.platform.architectureCore)
      implementation(projects.platform.processCore)
    }
    commonTest.dependencies {
      implementation(projects.platform.architectureTest)
    }
  }
}
