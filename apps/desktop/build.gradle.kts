import dev.lemonadecode.buildlogic.stripFfmpegPrograms
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

// The app is packaged from this classpath: FFmpeg's libraries go in, its command-line programs don't.
stripFfmpegPrograms()

// Release builds stamp the tag's version with -PappVersion=1.2.3 (see RELEASING.md).
val appVersion: String = providers.gradleProperty("appVersion").getOrElse("1.0.0")

// jpackage won't build a macOS app whose version starts with 0, so the bundle carries a 0.x version
// as 1.x (0.2.3 becomes 1.2.3). The app itself reads its real version from lemonadecode.version.
val macBundleVersion: String = appVersion.split('.').let { parts ->
  if (parts.first() == "0") (listOf("1") + parts.drop(1)).joinToString(".") else appVersion
}

compose.desktop {
  application {
    mainClass = "dev.lemonadecode.app.MainKt"
    jvmArgs(
      "--enable-native-access=ALL-UNNAMED",
      "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED",
      // What the update check compares with releases; jpackage's own property is 1.x for a 0.x Mac build.
      "-Dlemonadecode.version=$appVersion",
    )

    nativeDistributions {
      targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Msi)
      packageName = "Lemonade Code"
      packageVersion = appVersion
      description = "A terminal IDE for reviewing work"
      // Drawn by icons/make_icons.py.
      macOS {
        // Signing and notarization are switched on from the command line by the release workflow
        // (the compose.desktop.mac.* Gradle properties), so local builds stay unsigned.
        bundleID = "dev.lemonadecode.app"
        packageVersion = macBundleVersion
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
