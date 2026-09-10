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
package dev.elide.intellij.cli

import dev.elide.intellij.project.model.ElideBuildTaskInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins how the task listing printed by `elide build --inspect` is read. */
class ElideBuildTasksTest {
  /** Verbatim output of `elide build --inspect` for a JVM project declaring two JAR artifacts. */
  private val listing: String by lazy {
    checkNotNull(javaClass.getResourceAsStream("/cli/build-inspect.txt")) {
      "missing build task listing fixture"
    }.use { it.reader().readText() }
  }

  @Test fun `every task of the listing is read, and nothing else`() {
    val tasks = ElideBuildTasks.parse(listing)

    // the manifest's artifacts (`app`, `other-thing`) plus the tasks the CLI derives around them; the per-task
    // option rows and the trailing `Global options:` section are not tasks
    assertEquals(
      listOf(
        "app",
        "compile-java-main",
        "compile-java-test",
        "compile-kotlin-main",
        "maven-dependencies",
        "other-thing",
        "run",
        "run-app",
        "run-other-thing",
        "write-classpath-files",
      ),
      tasks.map { it.name },
    )
  }

  @Test fun `descriptions are kept, and are empty where the task declares none`() {
    val tasks = ElideBuildTasks.parse(listing).associate { it.name to it.description }

    assertEquals("Package compiled classes into a JAR archive", tasks["app"])
    assertEquals("Resolve and download Maven dependencies", tasks["maven-dependencies"])
    assertEquals("", tasks["write-classpath-files"])
  }

  @Test fun `the options of each task are read, and the empty listing declares none`() {
    val tasks = ElideBuildTasks.parse(listing).associateBy { it.name }

    assertEquals(
      listOf(
        ElideBuildTaskInfo.Option(
          "--fresh",
          "Re-download dependencies even if present in the local cache",
        ),
        ElideBuildTaskInfo.Option("--direct", "Copy artifacts to the project repository instead of symlinking"),
      ),
      tasks.getValue("maven-dependencies").options,
    )

    // `no options declared` stands in for an empty list, and is not itself an option
    assertEquals(emptyList(), tasks.getValue("app").options)
    assertEquals(listOf("--debugger", "--args"), tasks.getValue("run").options.map { it.option })
  }

  @Test fun `output without a task table yields no tasks`() {
    // a distribution that fails, prints a diagnostic, or drops the listing entirely must not be read as tasks
    assertTrue(ElideBuildTasks.parse("").isEmpty())
    assertTrue(ElideBuildTasks.parse("Error: No Elide project found\n").isEmpty())
    assertTrue(
      ElideBuildTasks.parse("Global options:\n  --no-cache   Disable the build cache for this run\n").isEmpty(),
    )
  }
}
