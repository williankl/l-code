plugins {
  id("lemonadecode.kmp.uimodel")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.platform.localizationCore)
      api(libs.compose.components.resources)
    }
  }
}

compose.resources {
  publicResClass = true
  generateResClass = org.jetbrains.compose.resources.ResourcesExtension.ResourceClassGeneration.Always
  packageOfResClass = "dev.lemonadecode.workspace.strings"
}
