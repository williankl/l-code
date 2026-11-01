/**
 * Structural baseline every Lemonade Code KMP module shares: toolchain, explicit API, compiler flags,
 * the desktop JVM target and Metro. Carries no library dependencies; the convention plugins
 * that compose it add their own.
 */
plugins {
  id("org.jetbrains.kotlin.multiplatform")
  id("dev.zacsweers.metro")
  id("lemonadecode.spotless")
}

metro {
  generateContributionProviders.set(true)
}

val libs = versionCatalogs.named("libs")

kotlin {
  jvmToolchain(libs.findVersion("jvmToolchain").get().requiredVersion.toInt())
  explicitApi()

  compilerOptions {
    freeCompilerArgs.add("-Xexpect-actual-classes")
    freeCompilerArgs.add("-Xreturn-value-checker=full")
  }

  jvm("desktop")
}
