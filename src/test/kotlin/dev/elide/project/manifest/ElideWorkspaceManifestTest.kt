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
package dev.elide.project.manifest

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the workspace half of the manifest contract: the member list a workspace root declares, and the references a
 * member writes as `project("model")` and `project("report").artifact("report")`.
 *
 * The fixtures are `elide manifest` output, captured per project of a four-member workspace, so a regenerated model
 * that stops understanding either fails here rather than at project import time — which is exactly how a mismatch
 * surfaces: an unknown union branch is a hard decoding failure, not an ignorable unknown key.
 */
class ElideWorkspaceManifestTest {
  private fun manifest(name: String) = ElideManifests.parse(
    requireNotNull(ElideWorkspaceManifestTest::class.java.getResourceAsStream("/manifest/workspace/$name.json"))
      .use { it.reader().readText() },
  )

  @Test fun `a workspace root declares its members as relative directories`() {
    assertEquals(listOf("model", "parser", "report", "cli"), manifest("root").workspaceMembers)

    // members are declared by the root alone: Elide's workspaces are two layers deep
    assertEquals(emptyList(), manifest("parser").workspaceMembers)
  }

  @Test fun `a dependency on a sibling project decodes as a project reference`() {
    // the reference names no artifact: `model` declares exactly one, and resolving it is that project's own business
    assertEquals(
      listOf(ProjectReference(project = "model", artifact = null, test = false)),
      manifest("parser").projectReferences(),
    )

    // `.artifact(…)` narrows the reference to one of several a project may declare
    assertEquals(
      listOf(
        ProjectReference(project = "parser", artifact = null, test = false),
        ProjectReference(project = "report", artifact = "report", test = false),
      ),
      manifest("cli").projectReferences(),
    )

    // a coordinate is not a project reference, and the root declares those and nothing else
    assertEquals(emptyList(), manifest("root").projectReferences())
  }

  @Test fun `an unnamed reference resolves to the single jar the referenced project declares`() {
    val model = manifest("model")

    assertEquals(listOf("main"), model.referencedSourceSets(artifact = null))
    assertEquals(listOf("main"), model.referencedSourceSets(artifact = "model"))

    // an artifact the project does not declare resolves to nothing; the CLI reports it when the build is configured
    assertEquals(emptyList(), model.referencedSourceSets(artifact = "fat"))
  }
}
