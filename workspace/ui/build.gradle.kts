import dev.lemonadecode.buildlogic.coordinates
import dev.lemonadecode.buildlogic.javacppPlatform

plugins {
  id("lemonadecode.kmp.feature")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.workspace.uimodel)
      api(projects.platform.designsystem)
      implementation(libs.compose.components.resources)
      implementation(libs.kotlinx.serialization.json)
    }
    getByName("desktopMain").dependencies {
      implementation(libs.jsvg)
      // Videos play through FFmpeg. JavaCV's own dependencies (OpenCV, Tesseract, …) aren't needed for that.
      implementation(coordinates(libs.javacv)) { isTransitive = false }
      implementation(libs.javacpp)
      implementation(coordinates(libs.javacpp, javacppPlatform))
      implementation(libs.ffmpeg)
      implementation(coordinates(libs.ffmpeg, javacppPlatform))
    }
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
