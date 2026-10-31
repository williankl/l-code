rootProject.name = "loupe"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
  includeBuild("build-logic")
  repositories {
    gradlePluginPortal()
    mavenCentral()
    google {
      mavenContent {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google")
      }
    }
  }
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") {
      mavenContent { includeGroup("org.jetbrains.jediterm") }
    }
    google {
      mavenContent {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google")
      }
    }
  }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Generic building blocks; depend only on each other.
include(":platform:core")
include(":platform:di-core")
include(":platform:architecture-core")
include(":platform:architecture-test")
include(":platform:presenter-core")
include(":platform:presenter-test")
include(":platform:preferences-core")
include(":platform:preferences-test")
include(":platform:process-core")
include(":platform:power-core")
include(":platform:syntax-core")
include(":platform:terminal")
include(":platform:designsystem")
include(":platform:localization-core")

// Pure domain models.
include(":domainEntities")

// Data sources.
include(":data:git:api")
include(":data:git:impl")
include(":data:git:fake")

// Features.
include(":sessions:api")
include(":sessions:impl")
include(":sessions:fake")
include(":review:api")
include(":review:impl")
include(":review:fake")
include(":settings:api")
include(":settings:impl")
include(":settings:fake")
include(":workspace:uimodel")
include(":workspace:ui")
include(":workspace:impl")

// Applications.
include(":apps:desktop")
include(":sample")
