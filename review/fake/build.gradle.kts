plugins {
  id("loupe.kmp.library")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      api(projects.review.api)
    }
  }
}
