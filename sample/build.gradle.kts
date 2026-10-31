import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  id("loupe.kmp.feature")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      implementation(projects.workspace.impl)
    }
    getByName("desktopMain").dependencies {
      implementation(compose.desktop.currentOs)
    }
  }
}

val desktopMainCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")

tasks.register<JavaExec>("renderScreens") {
  group = "loupe"
  description = "Renders every showcase screen to PNG under build/screens."
  dependsOn(desktopMainCompilation.compileAllTaskName)
  classpath(desktopMainCompilation.output.allOutputs, desktopMainCompilation.runtimeDependencyFiles)
  mainClass.set("dev.loupe.sample.RenderScreensKt")
  args(layout.buildDirectory.dir("screens").get().asFile.absolutePath)
  providers.gradleProperty("theme").orNull?.let { args(it) }
  jvmArgs("-Djava.awt.headless=true", "-Duser.language=" + (providers.gradleProperty("lang").orNull ?: "en"))
  javaLauncher.set(
    javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(libs.versions.jvmToolchain.get().toInt())) },
  )
}

compose.desktop {
  application {
    mainClass = "dev.loupe.sample.MainKt"

    nativeDistributions {
      targetFormats(TargetFormat.Dmg)
      packageName = "Loupe UI Sample"
      packageVersion = "1.0.0"
    }
  }
}
