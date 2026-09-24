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
package dev.elide.intellij.project.model

import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.XCollection
import dev.elide.intellij.Constants
import dev.elide.intellij.cli.ElideCli

/**
 * Serializable project data resolved from an Elide manifest during project sync.
 *
 * One entry is indexed per project the sync resolved, so a workspace contributes one for its root and one for every
 * member; [workspaceRoot] and [members] are what relate them, since only the root is a linked external project.
 */
data class ElideProjectInfo(
  /** Name the manifest declares for the project, or `null` when it declares none. */
  @Attribute val name: String? = null,
  /** Resolved entrypoints from the project's manifest. */
  @XCollection val entrypoints: List<ElideEntrypointInfo> = emptyList(),
  /** Tasks `elide build` accepts as targets in this project, as the CLI listed them during sync. */
  @XCollection val buildTasks: List<ElideBuildTaskInfo> = emptyList(),
  /** Artifacts of this project producing a runnable Native Image binary. */
  @XCollection val nativeImages: List<ElideNativeImageInfo> = emptyList(),
  /**
   * Path of the workspace root this project is a member of, or `null` when it is not a member of one.
   *
   * The root is the linked external project: a member is synced, indexed and built as part of it, and carries no
   * linked settings of its own.
   */
  @Attribute val workspaceRoot: String? = null,
  /** Paths of the member projects this project is the workspace root of, empty when it is the root of none. */
  @XCollection val members: List<String> = emptyList(),
) {
  companion object {
    /**
     * Returns the targets [data] exposes: entrypoints, in the order they are offered as run targets and completions,
     * and the build tasks the CLI listed for the project.
     */
    @JvmStatic fun from(data: ElideProjectData): ElideProjectInfo = ElideProjectInfo(
      name = data.name,
      entrypoints = buildList {
        // scripts can be used as tasks
        data.scripts.forEach { name -> add(ElideEntrypointInfo.script(name)) }

        // explicit entry points
        data.entrypoints.forEach { entrypoint -> add(ElideEntrypointInfo.generic(entrypoint)) }

        // the JVM main class, but only where a bare `elide run` actually reaches it: the CLI resolves the manifest's
        // `entrypoint` first and only then falls back to `jvm.main`, so with both declared a `run` entry for the main
        // class would start the other program. `elide build run` still runs it, and can be typed by hand
        if (data.entrypoints.isEmpty()) {
          data.jvmMainClass?.let { mainClassName -> add(ElideEntrypointInfo.jvmMain(mainClassName)) }
        }
      },
      buildTasks = data.buildTasks,
      nativeImages = data.nativeImages,
      workspaceRoot = data.workspaceRoot,
      members = data.members,
    )
  }
}

/** Returns the runnable Native Image the project's [artifact] produces, or `null` when it produces none. */
fun ElideProjectInfo.nativeImage(artifact: String): ElideNativeImageInfo? {
  return nativeImages.find { it.artifact == artifact }
}

/**
 * Describes a target an Elide project offers the IDE: an entrypoint a `run` starts, or an artifact a `build`
 * assembles. Prefer using the static factory functions to construct new instances, as they automatically set some of
 * the fields to the proper values.
 */
data class ElideEntrypointInfo(
  @Attribute val kind: Kind = Kind.Generic,
  @Attribute val displayName: String = "",
  @Attribute val descriptiveName: String = "",
  @Attribute val value: String = "",
) {
  /** Identifies the type of target, according to its source. */
  enum class Kind {
    Script,
    JvmMainClass,
    JvmTest,
    Generic,
    Artifact,
  }

  companion object {
    /** Returns an entrypoint resolved from a manifest script with the given [name]. */
    @JvmStatic fun script(name: String): ElideEntrypointInfo {
      return ElideEntrypointInfo(
        Kind.Script,
        displayName = name,
        descriptiveName = name,
        value = name,
      )
    }

    /** Returns a JVM entrypoint defined by its main class name in the manifest. */
    @JvmStatic fun jvmMain(mainClassName: String): ElideEntrypointInfo {
      val simpleName = mainClassName.substringAfterLast('.')

      return ElideEntrypointInfo(
        Kind.JvmMainClass,
        displayName = simpleName,
        descriptiveName = simpleName,
        value = mainClassName,
      )
    }

    /** Returns a generic entrypoint defined in the manifest by a relative path to a script or source file. */
    @JvmStatic fun generic(entrypoint: String): ElideEntrypointInfo {
      val simpleName = entrypoint.substringAfterLast('/')

      return ElideEntrypointInfo(
        Kind.Generic,
        displayName = simpleName,
        descriptiveName = simpleName,
        value = entrypoint,
      )
    }

    /**
     * Returns the build target for the artifact declared in the manifest under [name].
     *
     * The CLI names an artifact's build task after the artifact itself, so the name doubles as the `elide build`
     * target. The display name says what running it does, which a bare artifact name in the run widget would not.
     */
    @JvmStatic fun artifact(name: String): ElideEntrypointInfo {
      return ElideEntrypointInfo(
        Kind.Artifact,
        displayName = Constants.Strings["execution.configuration.artifact", name],
        descriptiveName = name,
        value = name,
      )
    }
  }
}

/**
 * Returns the raw base command line for the Elide CLI that can be used to invoke this target.
 *
 * The value is quoted the way [ElideRunConfiguration][dev.elide.intellij.execution.ElideRunConfiguration] parses it,
 * so entrypoint paths and artifact names containing spaces survive the round trip into the configuration's argument
 * vector.
 */
val ElideEntrypointInfo.fullCommandLine: String
  get() = when (kind) {
    ElideEntrypointInfo.Kind.JvmMainClass -> ElideCli.RUN.name
    ElideEntrypointInfo.Kind.Artifact -> ParametersListUtil.join(ElideCli.BUILD.name, value)
    else -> ParametersListUtil.join(ElideCli.RUN.name, value)
  }
