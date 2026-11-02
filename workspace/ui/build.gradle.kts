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
    }
  }
}
