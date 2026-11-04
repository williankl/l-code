plugins {
  id("lemonadecode.kmp.feature")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.workspace.ui)
      api(projects.platform.presenterCore)
      implementation(projects.sessions.api)
      implementation(projects.review.api)
      implementation(projects.settings.api)
      implementation(projects.updates.api)
      implementation(projects.mobile.api)
      implementation(projects.platform.architectureCore)
      implementation(projects.platform.syntaxCore)
      implementation(projects.platform.processCore)
      implementation(projects.platform.localizationCore)
      implementation(projects.platform.soundCore)
      implementation(libs.compose.components.resources)
    }
    commonTest.dependencies {
      implementation(projects.sessions.fake)
      implementation(projects.review.fake)
      implementation(projects.settings.fake)
      implementation(projects.updates.fake)
      implementation(projects.mobile.fake)
      implementation(projects.platform.presenterTest)
      implementation(projects.platform.architectureTest)
      implementation(projects.platform.soundTest)
    }
  }
}

kotlin {
  sourceSets {
    getByName("desktopTest").dependencies {
      implementation(libs.compose.ui.test)
      implementation(compose.desktop.currentOs)
    }
  }
}

tasks.withType<Test>().configureEach {
  // UI tests assert on English copy; the strings follow the JVM's locale.
  jvmArgs("-Djava.awt.headless=true", "-Duser.language=en", "-Duser.country=US")
}
