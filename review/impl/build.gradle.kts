plugins {
  id("lemonadecode.kmp.library")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.review.api)
      implementation(projects.sessions.api)
      implementation(projects.data.git.api)
      implementation(projects.platform.architectureCore)
      implementation(projects.platform.syntaxCore)
    }
    commonTest.dependencies {
      implementation(projects.sessions.fake)
      implementation(projects.data.git.fake)
      implementation(projects.platform.architectureTest)
      implementation(libs.turbine)
    }
  }
}
