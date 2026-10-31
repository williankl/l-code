plugins {
  id("loupe.kmp.feature")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.workspace.uimodel)
      api(projects.platform.designsystem)
      implementation(libs.compose.components.resources)
    }
  }
}
