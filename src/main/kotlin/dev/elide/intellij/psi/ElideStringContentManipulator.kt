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
package dev.elide.intellij.psi

import com.intellij.openapi.util.TextRange
import com.intellij.psi.AbstractElementManipulator
import org.pkl.intellij.psi.PklStringContentBase

/**
 * Rewrites the content of a Pkl string literal, which is what a reference into one does when its target is renamed or
 * moved: a `workspace.members` entry follows the directory it names.
 *
 * The Pkl plugin registers no manipulator for string contents, since it attaches no references to them itself.
 */
class ElideStringContentManipulator : AbstractElementManipulator<PklStringContentBase>() {
  override fun handleContentChange(element: PklStringContentBase, range: TextRange, newContent: String) =
    element.updateText(range.replace(element.text, newContent)) as PklStringContentBase
}
