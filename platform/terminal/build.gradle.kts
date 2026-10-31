plugins {
  id("loupe.kmp.compose")
}

kotlin {
  sourceSets {
    getByName("desktopMain").dependencies {
      api(libs.pty4j)
      api(libs.jediterm.core)
      implementation(libs.compose.foundation)
      implementation(libs.compose.ui)
    }
    getByName("desktopTest").dependencies {
      implementation(compose.desktop.currentOs)
    }
  }
}

tasks.withType<Test>().configureEach {
  jvmArgs("-Djava.awt.headless=true")
}
