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
package dev.elide.intellij

import com.intellij.openapi.externalSystem.model.ExternalSystemException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins the failure contract the external system infrastructure renders: plugin failures are
 * [ExternalSystemException]s and carry a localized, actionable message.
 */
class ExceptionsTest {
  @Test fun `invalid distribution reports the offending path`() {
    val exception = InvalidElideHomeException("/opt/not-elide")

    assertIs<ExternalSystemException>(exception)
    assertContains(exception.message.orEmpty(), "/opt/not-elide")
    assertResolvedMessage(exception)
  }

  @Test fun `missing manifest reports the project path`() {
    val exception = MissingManifestException("/projects/demo")

    assertIs<ExternalSystemException>(exception)
    assertContains(exception.message.orEmpty(), "/projects/demo")
    assertContains(exception.message.orEmpty(), Constants.MANIFEST_NAME)
    assertResolvedMessage(exception)
  }

  @Test fun `command failures carry the exit code and stderr`() {
    val exception = ElideCommandFailedException(listOf("elide", "install"), 3, "dependency not found")

    assertIs<ExternalSystemException>(exception)
    assertContains(exception.message.orEmpty(), "elide install")
    assertContains(exception.message.orEmpty(), "3")
    assertContains(exception.message.orEmpty(), "dependency not found")
    assertResolvedMessage(exception)
  }

  private fun assertResolvedMessage(exception: Throwable) {
    // an unresolved bundle key renders as `!key!`
    assertTrue(
      !exception.message.orEmpty().startsWith("!"),
      "message was not resolved from the resource bundle: ${exception.message}",
    )
  }
}
