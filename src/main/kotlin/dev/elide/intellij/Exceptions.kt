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
import com.intellij.openapi.util.io.toCanonicalPath
import java.nio.file.Path

/**
 * Base class for failures raised by the plugin during project resolution or task execution.
 *
 * Extending [ExternalSystemException] is deliberate: the external system infrastructure renders these with the build
 * tool window affordances (failure node, "original reason" text, quick fixes) instead of the generic internal error
 * report used for arbitrary runtime exceptions.
 */
sealed class ElidePluginException(message: String, cause: Throwable? = null) :
  ExternalSystemException(message, cause)

/** Raised when the configured Elide distribution does not contain a usable CLI binary. */
class InvalidElideHomeException(path: String) : ElidePluginException(
  Constants.Strings["errors.invalidElideHome", path],
) {
  constructor(path: Path) : this(path.toCanonicalPath())
}

/** Raised when a linked external project directory does not contain an Elide manifest. */
class MissingManifestException(projectPath: String) : ElidePluginException(
  Constants.Strings["errors.missingManifest", Constants.MANIFEST_NAME, projectPath],
)

/**
 * Raised when the Elide CLI exits with a non-zero status. The captured [stderr] output is part of the message so the
 * sync log shows the actual failure reported by the CLI rather than only an exit code.
 */
class ElideCommandFailedException(
  val command: List<String>,
  val exitCode: Int,
  val stderr: String,
) : ElidePluginException(
  buildString {
    append(Constants.Strings["errors.commandFailed", command.joinToString(" "), exitCode])
    if (stderr.isNotBlank()) {
      append('\n')
      append(stderr)
    }
  },
)
