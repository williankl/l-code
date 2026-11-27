plugins {
  id("lemonadecode.kmp.compose")
}

kotlin {
  sourceSets {
    getByName("desktopMain").dependencies {
      api(libs.pty4j)
      api(libs.jediterm.core)
      // Asks macOS's kernel about the shell's processes; see DarwinProcess.
      implementation(libs.jna)
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
