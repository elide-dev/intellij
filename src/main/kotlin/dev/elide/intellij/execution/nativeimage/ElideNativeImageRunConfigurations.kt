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

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import dev.elide.intellij.execution.ElideBeforeRunTaskProvider

/**
 * Creation and lookup of [ElideNativeImageRunConfiguration]s, shared by everything offering to run an image: the
 * manifest's gutter, the tool window's task nodes, and whatever else names an artifact.
 *
 * Going through one place is what keeps the two paths from disagreeing on what a run of an artifact is — notably on
 * the before-launch task that builds it, which is part of the configuration rather than of the action starting it.
 */
object ElideNativeImageRunConfigurations {
  /** Points [configuration] at [artifact] of the project at [externalProjectPath], building it before it launches. */
  @JvmStatic fun configure(
    configuration: ElideNativeImageRunConfiguration,
    externalProjectPath: String,
    artifact: String,
  ) {
    configuration.name = artifact
    configuration.externalProjectPath = externalProjectPath
    configuration.artifact = artifact
    configuration.beforeRunTasks = listOf(ElideBeforeRunTaskProvider.buildTask(externalProjectPath, artifact))
  }

  /** Returns whether [configuration] runs [artifact] of the project at [externalProjectPath]. */
  @JvmStatic fun matches(
    configuration: ElideNativeImageRunConfiguration,
    externalProjectPath: String,
    artifact: String,
  ): Boolean {
    if (configuration.artifact != artifact) return false

    return configuration.externalProjectPath?.let { FileUtil.pathsEqual(it, externalProjectPath) } == true
  }

  /**
   * Returns the configuration running [artifact] of the project at [externalProjectPath], adding one to the run
   * manager when none exists yet.
   *
   * Reusing the saved configuration is what lets the settings a user put on it — program arguments, a working
   * directory, environment — survive the next start from the tool window or the gutter.
   */
  @JvmStatic fun findOrCreate(
    project: Project,
    externalProjectPath: String,
    artifact: String,
  ): RunnerAndConfigurationSettings {
    val runManager = RunManager.getInstance(project)
    val existing = runManager.allSettings.find { settings ->
      (settings.configuration as? ElideNativeImageRunConfiguration)
        ?.let { matches(it, externalProjectPath, artifact) } == true
    }

    if (existing != null) return existing

    val settings = runManager.createConfiguration(artifact, ElideNativeImageConfigurationType.factory)
    configure(settings.configuration as ElideNativeImageRunConfiguration, externalProjectPath, artifact)
    runManager.addConfiguration(settings)

    return settings
  }
}
