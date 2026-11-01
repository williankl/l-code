import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  id("lemonadecode.kmp.feature")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      implementation(projects.workspace.impl)
      implementation(projects.sessions.impl)
      implementation(projects.review.impl)
      implementation(projects.settings.impl)
      implementation(projects.updates.impl)
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
    mainClass = "dev.lemonadecode.app.MainKt"
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED")

    nativeDistributions {
      targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Msi)
      packageName = "Lemonade Code"
      // Release builds stamp the tag's version with -PappVersion=1.2.3 (see RELEASING.md).
      packageVersion = providers.gradleProperty("appVersion").getOrElse("1.0.0")
      description = "A terminal IDE for reviewing work"
      // Drawn by icons/make_icons.py.
      macOS {
        // Signing and notarization are switched on from the command line by the release workflow
        // (the compose.desktop.mac.* Gradle properties), so local builds stay unsigned.
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
        menu = true
        menuGroup = "Lemonade Code"
        // For the current user only, so installing a newer version (the app updating itself) needs no admin rights.
        perUserInstall = true
        // Ties every version's MSI to the same install, so a newer one replaces the older. Never change it.
        upgradeUuid = "157e89ef-e60c-4b37-9f08-bb011f0521a0"
      }
    }
  }
}
