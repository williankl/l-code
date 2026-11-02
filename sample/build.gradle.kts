import dev.lemonadecode.buildlogic.stripFfmpegPrograms
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  id("lemonadecode.kmp.feature")
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

// Packaged as an app too: FFmpeg's libraries go in, its command-line programs don't.
stripFfmpegPrograms()

val desktopMainCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")

tasks.register<JavaExec>("renderScreens") {
  group = "lemonade code"
  description = "Renders every showcase screen to PNG under build/screens."
  dependsOn(desktopMainCompilation.compileAllTaskName)
  classpath(desktopMainCompilation.output.allOutputs, desktopMainCompilation.runtimeDependencyFiles)
  mainClass.set("dev.lemonadecode.sample.RenderScreensKt")
  args(layout.buildDirectory.dir("screens").get().asFile.absolutePath)
  providers.gradleProperty("theme").orNull?.let { args(it) }
  jvmArgs("-Djava.awt.headless=true", "-Duser.language=" + (providers.gradleProperty("lang").orNull ?: "en"))
  javaLauncher.set(
    javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(libs.versions.jvmToolchain.get().toInt())) },
  )
}

compose.desktop {
  application {
    mainClass = "dev.lemonadecode.sample.MainKt"

    nativeDistributions {
      targetFormats(TargetFormat.Dmg)
      packageName = "Lemonade Code UI Sample"
      packageVersion = "1.0.0"
    }
  }
}
