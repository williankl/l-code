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
    jvmArgs("--enable-native-access=ALL-UNNAMED")

    nativeDistributions {
      targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Msi)
      packageName = "Loupe"
      packageVersion = "1.0.0"
      macOS {
        bundleID = "dev.loupe.app"
      }
    }
  }
}
