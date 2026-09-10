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
package dev.elide.intellij.ui

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.task.TaskData
import dev.elide.intellij.Constants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pins the labels the Elide tool window's tree shows for build tasks. */
class ElideViewContributorTest {
  private val contributor = ElideViewContributor()

  @Test fun `tasks are labelled with their target`() {
    val task = TaskData(Constants.SYSTEM_ID, ":compile-kotlin-main", "/projects/demo", null)

    assertEquals("compile-kotlin-main", contributor.getDisplayName(DataNode(ProjectKeys.TASK, task, null)))
  }

  @Test fun `nodes without a target name are left to the platform`() {
    val module = ModuleData("demo", Constants.SYSTEM_ID, "JAVA_MODULE", "demo", "/projects/demo", "/projects/demo")

    assertNull(contributor.getDisplayName(DataNode(ProjectKeys.MODULE, module, null)))
    assertNull(
      contributor.getDisplayName(
        DataNode(ProjectKeys.TASK, TaskData(Constants.SYSTEM_ID, "run", "/projects/demo", null), null),
      ),
    )
  }
}
