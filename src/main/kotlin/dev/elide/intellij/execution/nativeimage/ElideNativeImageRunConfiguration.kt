/*
 * Copyright (c) 2024-2025 Elide Technologies, Inc.
 *
 * Licensed under the MIT license (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   https://opensource.org/license/mit/
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under the License.
 */
package dev.elide.intellij.execution.nativeimage

import com.intellij.execution.CommonProgramRunConfigurationParameters
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.SimpleProgramParameters
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.util.ProgramParametersUtil
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import dev.elide.intellij.Constants
import dev.elide.intellij.Constants.Strings
import dev.elide.intellij.project.model.ElideNativeImages
import dev.elide.intellij.project.model.nativeImage
import dev.elide.intellij.service.elideProjectIndex
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.Icon

/**
 * Runs the binary a Native Image artifact of an Elide project produces.
 *
 * The CLI assembles the image and stops there, so the launch is an ordinary local process: the artifact is built by
 * the before-launch task [ElideBeforeRunTaskProvider][dev.elide.intellij.execution.ElideBeforeRunTaskProvider] adds,
 * and this configuration starts what the build left in the project's artifact directory.
 *
 * The output path is not reported by the CLI; it is reconstructed from the manifest facts the sync collected (see
 * [ElideNativeImages]), which is why a launch resolves through the project index rather than the manifest.
 */
class ElideNativeImageRunConfiguration(
  project: Project,
  factory: ConfigurationFactory,
  name: String,
) : LocatableConfigurationBase<ElideNativeImageRunConfiguration.Options>(project, factory, name),
  CommonProgramRunConfigurationParameters {
  /** Persistent state of a Native Image run configuration. */
  class Options : LocatableRunConfigurationOptions() {
    var externalProjectPath by string()
    var artifact by string()
    var programParameters by string()
    var workingDirectory by string()
    var envs by map<String, String>()
    var passParentEnvs by property(true)
  }

  override fun getOptions(): Options = super.getOptions() as Options

  /** Directory of the Elide project declaring the artifact. */
  var externalProjectPath: String?
    get() = options.externalProjectPath
    set(value) {
      options.externalProjectPath = value
    }

  /** Key the image is declared under in the project's manifest; also the target `elide build` is given. */
  var artifact: String?
    get() = options.artifact
    set(value) {
      options.artifact = value
    }

  override fun getIcon(): Icon = Constants.Icons.ELIDE

  override fun getConfigurationEditor(): SettingsEditor<ElideNativeImageRunConfiguration> {
    return ElideNativeImageSettingsEditor(project)
  }

  override fun suggestedName(): String? = artifact

  override fun checkConfiguration() {
    val root = externalProjectPath?.takeUnless { it.isBlank() }?.let(::pathOrNull)
    if (root == null || !Files.isDirectory(root)) {
      throw RuntimeConfigurationError(Strings["execution.nativeImage.error.noProject"])
    }

    if (artifact.isNullOrBlank()) throw RuntimeConfigurationError(Strings["execution.nativeImage.error.noArtifact"])
  }

  override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
    return ElideNativeImageRunState(environment, this)
  }

  /**
   * Returns what it takes to start the artifact's binary, resolving where the build left it.
   *
   * Resolution is deliberately late: the before-launch task builds the image, so the file only exists by the time a
   * runner asks for this, and a configuration created before the first sync still names the path the CLI would use.
   *
   * @throws ExecutionException When the configuration names no project or artifact, or the build produced no binary.
   */
  fun resolveLaunch(): ElideNativeImageLaunch {
    val projectPath = externalProjectPath?.takeUnless { it.isBlank() }
      ?: throw ExecutionException(Strings["execution.nativeImage.error.noProject"])
    val artifact = artifact?.takeUnless { it.isBlank() }
      ?: throw ExecutionException(Strings["execution.nativeImage.error.noArtifact"])
    val root = pathOrNull(projectPath) ?: throw ExecutionException(Strings["execution.nativeImage.error.noProject"])

    val info = project.elideProjectIndex[projectPath]
    val binary = ElideNativeImages.binary(
      root = root,
      outputName = info?.nativeImage(artifact)?.outputName,
      projectName = info?.name,
    )

    if (!Files.isRegularFile(binary)) {
      throw ExecutionException(Strings["execution.nativeImage.error.missingBinary", artifact, binary.toString()])
    }

    val parameters = SimpleProgramParameters().also { ProgramParametersUtil.configureConfiguration(it, this) }

    // the working directory is resolved from the raw option rather than from the expanded parameters: the platform
    // substitutes `project.basePath` for a blank one and resolves a relative one against it, which is the wrong
    // root for an Elide project linked outside the IDE's. Macros are still expanded, and a relative entry means
    // "relative to the Elide project"
    val workDir = workingDirectory?.takeUnless { it.isBlank() }
      ?.let { ProgramParametersUtil.expandPathAndMacros(it, null, project) }
      ?.takeUnless { it.isBlank() }
      ?.let(::pathOrNull)
      ?.let(root::resolve)
      ?: root

    val commandLine = GeneralCommandLine(binary.toString())
      .withParameters(parameters.programParametersList.list)
      .withWorkingDirectory(workDir)
      .withEnvironment(parameters.env)
      .withParentEnvironmentType(
        if (parameters.isPassParentEnvs) GeneralCommandLine.ParentEnvironmentType.CONSOLE
        else GeneralCommandLine.ParentEnvironmentType.NONE,
      )

    return ElideNativeImageLaunch(root = root, binary = binary, commandLine = commandLine)
  }

  override fun getProgramParameters(): String? = options.programParameters

  override fun setProgramParameters(value: String?) {
    options.programParameters = value
  }

  override fun getWorkingDirectory(): String? = options.workingDirectory

  override fun setWorkingDirectory(value: String?) {
    options.workingDirectory = value
  }

  override fun getEnvs(): MutableMap<String, String> = options.envs

  override fun setEnvs(envs: MutableMap<String, String>) {
    options.envs = envs
  }

  override fun isPassParentEnvs(): Boolean = options.passParentEnvs

  override fun setPassParentEnvs(passParentEnvs: Boolean) {
    options.passParentEnvs = passParentEnvs
  }

  private fun pathOrNull(value: String): Path? = try {
    Path.of(value)
  } catch (_: InvalidPathException) {
    null
  }
}
