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
package dev.elide.intellij.wizard

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import dev.elide.intellij.cli.ElideCommandLine
import dev.elide.intellij.cli.ElideTemplate
import dev.elide.intellij.cli.templates
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Application service listing the project templates of an Elide distribution.
 *
 * Listing spawns the CLI, so it is never done on the UI thread; the wizard shows the distribution's templates as they
 * arrive, and a distribution edited again cancels the [Job] of the previous lookup.
 */
@Service(Service.Level.APP)
class ElideTemplateLoader(private val scope: CoroutineScope) {
  /**
   * List the templates at [elideHome], invoking [onResult] on the UI thread with the outcome.
   *
   * The result is dispatched at [ModalityState.any]. `EdtCoroutineDispatcher` takes the dispatch modality from the
   * coroutine context, and `Dispatchers.EDT` on its own supplies the default, non-modal one -- so the callback queues
   * behind the modal New Project dialog that is waiting for it, and the wizard sits on "loading" until it closes.
   *
   * Capturing the caller's modality instead is not reliable here: a generator's panel is built when that generator
   * becomes selected, which for the dialog's preselected generator happens before it is shown, while the step's
   * components are not in a displayed window and every modality accessor still answers non-modal. `any()` does not
   * depend on when the panel is built.
   *
   * That is safe because the callback touches nothing outside the dialog: the status label, the combo box model, and
   * this step's own properties. No PSI, document, or project-model state is involved -- the case `any()` is reserved
   * for.
   */
  fun load(elideHome: Path, onResult: (Result<List<ElideTemplate>>) -> Unit): Job = scope.launch {
    // `runCatching` would swallow the cancellation of a superseded lookup along with real failures
    val result = try {
      Result.success(ElideCommandLine.at(elideHome).templates())
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (failure: Throwable) {
      Result.failure(failure)
    }

    withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { onResult(result) }
  }
}
