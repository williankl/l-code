import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  id("loupe.kmp.feature")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      implementation(projects.workspace.impl)
      implementation(projects.sessions.impl)
      implementation(projects.review.impl)
      implementation(projects.settings.impl)
      implementation(projects.data.git.impl)
      implementation(projects.platform.architectureCore)
      implementation(projects.platform.preferencesCore)
      implementation(projects.platform.processCore)
      implementation(projects.platform.powerCore)
    }
    getByName("desktopMain").dependencies {
      implementation(compose.desktop.currentOs)
      implementation(libs.kotlinx.coroutines.swing)
    }
    getByName("desktopTest").dependencies {
      implementation(compose.desktop.currentOs)
    }
  }
}

tasks.withType<Test>().configureEach {
  jvmArgs("-Djava.awt.headless=true")
}

compose.desktop {
  application {
    mainClass = "dev.loupe.app.MainKt"
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED")

    nativeDistributions {
      targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Msi)
      packageName = "Lemonade Code"
      packageVersion = "1.0.0"
      description = "A terminal IDE for reviewing work"
      // Drawn by icons/make_icons.py.
      macOS {
        bundleID = "dev.lemonadecode.app"
        iconFile.set(project.file("icons/lemonade-code.icns"))
      }
      linux {
        packageName = "lemonade-code"
        iconFile.set(project.file("icons/lemonade-code.png"))
        shortcut = true
        menuGroup = "Development"
        appCategory = "Development"
      }
      windows {
        iconFile.set(project.file("icons/lemonade-code.ico"))
        shortcut = true
        menuGroup = "Lemonade Code"
      }
    }
  }
}
