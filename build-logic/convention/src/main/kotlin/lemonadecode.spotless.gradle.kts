/**
 * Formatting: ktlint through Spotless, configured by the root `.editorconfig`.
 * `./gradlew spotlessApply` formats, `./gradlew spotlessCheck` verifies.
 */
plugins {
  id("com.diffplug.spotless")
}

val libs = versionCatalogs.named("libs")
val ktlintVersion = libs.findVersion("ktlint").get().requiredVersion

spotless {
  kotlin {
    target("src/**/*.kt")
    targetExclude("**/build/**")
    ktlint(ktlintVersion)
  }
  kotlinGradle {
    target("*.gradle.kts")
    ktlint(ktlintVersion)
  }
}
