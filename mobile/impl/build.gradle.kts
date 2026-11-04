import dev.lemonadecode.buildlogic.coordinates
import dev.lemonadecode.buildlogic.javacppPlatform

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
      // Mirrored screens are decoded with FFmpeg's H.264 decoder, without the rest of JavaCV.
      implementation(libs.javacpp)
      implementation(coordinates(libs.javacpp, javacppPlatform))
      implementation(libs.ffmpeg)
      implementation(coordinates(libs.ffmpeg, javacppPlatform))
    }
    commonTest.dependencies {
      implementation(projects.settings.fake)
      implementation(projects.platform.architectureTest)
      implementation(libs.turbine)
    }
  }
}
