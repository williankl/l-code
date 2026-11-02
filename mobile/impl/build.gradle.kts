plugins {
  id("lemonadecode.kmp.library")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.mobile.api)
      implementation(projects.settings.api)
      implementation(projects.platform.architectureCore)
      implementation(projects.platform.processCore)
    }
    getByName("desktopMain").dependencies {
      implementation(libs.zxing.core)
    }
    commonTest.dependencies {
      implementation(projects.settings.fake)
      implementation(projects.platform.architectureTest)
      implementation(libs.turbine)
    }
  }
}
