package dev.lemonadecode.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The JavaCPP platform FFmpeg's native libraries are picked for, like `macosx-arm64`: the machine's
 * own, since every release is built on the OS and architecture it's for. `-Pjavacpp.platform=…`
 * picks another.
 */
val Project.javacppPlatform: String
  get() = providers.gradleProperty("javacpp.platform").orNull ?: hostJavacppPlatform()

private fun hostJavacppPlatform(): String {
  val os = System.getProperty("os.name").lowercase()
  val arch = System.getProperty("os.arch").lowercase()
  val system = when {
    os.startsWith("mac") || os.startsWith("darwin") -> "macosx"
    os.startsWith("windows") -> "windows"
    else -> "linux"
  }
  val cpu = when (arch) {
    "aarch64", "arm64" -> "arm64"
    "x86_64", "amd64", "x64" -> "x86_64"
    else -> error("FFmpeg has no natives for $arch; pass -Pjavacpp.platform=<platform>")
  }
  return "$system-$cpu"
}

/** [library]'s coordinates, with [classifier] when given, for a dependency the catalog can't spell. */
fun coordinates(library: Provider<MinimalExternalModuleDependency>, classifier: String? = null): String {
  val dependency = library.get()
  val notation = "${dependency.module}:${dependency.versionConstraint.requiredVersion}"
  return if (classifier == null) notation else "$notation:$classifier"
}

private val ffmpegProgramsStripped = Attribute.of("dev.lemonadecode.ffmpegProgramsStripped", Boolean::class.javaObjectType)

/**
 * Takes the `ffmpeg` and `ffprobe` programs out of FFmpeg's jars on [configuration], the classpath
 * the app is packaged from. The app only loads FFmpeg's libraries, and Apple's notary service rejects
 * the programs: Compose signs the `.dylib`s in jars but not files without an extension.
 */
fun Project.stripFfmpegPrograms(configuration: String = "desktopRuntimeClasspath") {
  dependencies.attributesSchema.attribute(ffmpegProgramsStripped)
  dependencies.artifactTypes.maybeCreate(ArtifactTypeDefinition.JAR_TYPE).attributes.attribute(ffmpegProgramsStripped, false)
  dependencies.registerTransform(StripFfmpegPrograms::class.java) {
    from.attribute(ffmpegProgramsStripped, false).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE)
    to.attribute(ffmpegProgramsStripped, true).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE)
  }
  configurations.matching { it.name == configuration }.configureEach {
    attributes.attribute(ffmpegProgramsStripped, true)
  }
}

/** Copies a jar without FFmpeg's programs; every other jar passes through untouched. */
abstract class StripFfmpegPrograms : TransformAction<TransformParameters.None> {
  @get:InputArtifact
  @get:PathSensitive(PathSensitivity.NAME_ONLY)
  abstract val input: Provider<FileSystemLocation>

  override fun transform(outputs: TransformOutputs) {
    val jar = input.get().asFile
    if (!jar.isFile || !jar.name.startsWith("ffmpeg-")) {
      outputs.file(jar)
      return
    }
    ZipFile(jar).use { zip ->
      if (zip.entries().asSequence().none { Programs.matches(it.name) }) {
        outputs.file(jar)
        return
      }
      ZipOutputStream(outputs.file(jar.name).outputStream().buffered()).use { out ->
        zip.entries().asSequence().filterNot { Programs.matches(it.name) }.forEach { entry ->
          out.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
          zip.getInputStream(entry).use { it.copyTo(out) }
          out.closeEntry()
        }
      }
    }
  }

  private companion object {
    val Programs = Regex("org/bytedeco/ffmpeg/[^/]+/(ffmpeg|ffprobe)(\\.exe)?")
  }
}
