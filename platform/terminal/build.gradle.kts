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
      // DarwinProcessParents, the kernel's answer to process-core's ProcessParents.
      api(projects.platform.processCore)
      implementation(projects.platform.diCore)
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
