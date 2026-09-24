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

import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.XCollection
import dev.elide.intellij.cli.ElideCli
import java.io.Serializable

/**
 * A task in a project's build graph, as listed by `elide build --inspect`.
 *
 * A task's [name] is exactly what `elide build` accepts as a positional target. Only some tasks come from the
 * manifest verbatim (an `artifacts` entry is a task named after itself); the rest are derived by the CLI from source
 * sets, dependencies and entrypoints (`compile-kotlin-main`, `maven-dependencies`, `run-app`, …), so the list is read
 * from the CLI rather than computed from the manifest.
 *
 * Instances travel both in the resolved project node ([ElideProjectData], Java serialization) and in the persisted
 * project index ([ElideProjectInfo], XML), hence both the [Serializable] marker and the XML annotations.
 */
data class ElideBuildTaskInfo(
  @Attribute val name: String = "",
  @Attribute val description: String = "",
  /** Options the task declares; the CLI accepts them once the task is named as a target. */
  @XCollection val options: List<Option> = emptyList(),
) : Serializable {
  /**
   * An option a task declares.
   *
   * The listing prints the option exactly as it is written on the command line, dashes included, and says nothing
   * about whether it takes a value, so [option] is the whole of what the CLI reveals about its shape.
   */
  data class Option(
    @Attribute val option: String = "",
    @Attribute val description: String = "",
  ) : Serializable {
    private companion object {
      private const val serialVersionUID: Long = 1L
    }
  }

  private companion object {
    private const val serialVersionUID: Long = 1L
  }
}

/**
 * Prefix marking an external system task name as a target of the project's build graph.
 *
 * The CLI takes a target with or without it (`elide build :app` and `elide build app` name the same one) and rejects
 * it anywhere else — `elide :app` resolves no file and no script — so a task name carrying the prefix names a build
 * target and nothing else. That is what lets [buildCommandLine] tell the targets the platform asks for by name (the
 * tool window's "Run", task activation, keymap shortcuts) from the argument vector a run configuration was written
 * with.
 */
const val BUILD_TARGET_PREFIX: String = ":"

/** The name this task carries in the project model, and therefore in the settings of a run that executes it. */
val ElideBuildTaskInfo.taskName: String get() = "$BUILD_TARGET_PREFIX$name"

/** Returns the build target [taskName] names, or `null` when it names none. */
fun buildTargetName(taskName: String): String? {
  return taskName.removePrefix(BUILD_TARGET_PREFIX).takeIf { it.length < taskName.length && it.isNotEmpty() }
}

/**
 * Separator between the project a build task belongs to and the task's own name.
 *
 * Inside a workspace the CLI qualifies every task with the name of the project declaring it — `core:jar` — the
 * root's own tasks included; a distribution predating that leaves the root's bare, and both are read. A project
 * accepts its own tasks unqualified when it is the project in focus, which is what a run rooted at that project is.
 */
const val BUILD_TASK_SCOPE_SEPARATOR: Char = ':'

/**
 * The project this task's name qualifies it with, or `null` when it carries no scope and the task therefore belongs
 * to the project the listing was read for.
 */
val ElideBuildTaskInfo.taskScope: String? get() = name.substringBefore(BUILD_TASK_SCOPE_SEPARATOR, "").ifEmpty { null }

/** The task's own name, with the project scope the CLI qualified it with dropped. */
fun unqualifiedTaskName(name: String): String = name.substringAfterLast(BUILD_TASK_SCOPE_SEPARATOR)

/**
 * Returns the tasks of [project] among [tasks], named the way that project accepts them.
 *
 * The scope is dropped, so `core:jar` becomes `jar`: the name a build rooted at the member resolves. Tasks of other
 * projects, and any the CLI left unqualified, are left out.
 */
fun buildTasksOf(project: String, tasks: List<ElideBuildTaskInfo>): List<ElideBuildTaskInfo> {
  val scope = "$project$BUILD_TASK_SCOPE_SEPARATOR"

  return tasks.mapNotNull { task ->
    task.name.takeIf { it.startsWith(scope) }?.let { task.copy(name = it.removePrefix(scope)) }
  }
}

/**
 * Returns the argument vector that builds the targets [taskNames] opens with, or `null` when it names none and is
 * therefore an Elide argument vector already.
 *
 * The shape follows the one `elide build` documents — `build [TARGET…] [--OPTION…]` — so the leading targets are
 * translated and everything behind them passed through: a configuration created for a task of the tool window
 * keeps working once options are typed into it.
 *
 * The prefix is dropped on the way out, since the bare name is the form the manifest and the `--inspect` listing
 * use, and the CLI takes either.
 */
fun buildCommandLine(taskNames: List<String>): List<String>? {
  val targets = taskNames.asSequence()
    .map(::buildTargetName)
    .takeWhile { it != null }
    .filterNotNull()
    .toList()

  if (targets.isEmpty()) return null

  return listOf(ElideCli.BUILD.name) + targets + taskNames.drop(targets.size)
}
