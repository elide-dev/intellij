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

import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.codeInsight.highlighting.HighlightedReference
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReference
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReferenceSet
import dev.elide.intellij.Constants
import org.pkl.intellij.psi.PklModule
import org.pkl.intellij.psi.PklObjectEntry
import org.pkl.intellij.psi.PklStringContent

/** Returns the references a workspace string carries, or an empty array when [element] is none. */
internal fun workspaceReferences(element: PklStringContent): Array<out PsiReference> {
  // an interpolated string has no constant value to resolve
  if (element.exprList.isNotEmpty()) return PsiReference.EMPTY_ARRAY

  return when (val kind = element.workspaceString()) {
    null -> PsiReference.EMPTY_ARRAY
    ManifestWorkspaceString.MemberPath -> ElideMemberPathReferenceSet(element).allReferences
    ManifestWorkspaceString.ProjectName -> arrayOf(ElideProjectNameReference(element))
    is ManifestWorkspaceString.ProjectArtifactName -> arrayOf(ElideProjectArtifactReference(element, kind.project))
    is ManifestWorkspaceString.LocalArtifactName -> arrayOf(ElideLocalArtifactReference(element, kind.declaration))
  }
}

/** The text a reference in [element] names, without the placeholder the completion machinery injects. */
private fun referenceText(element: PsiElement): String {
  return element.text.replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "")
}

// -- Member paths

/**
 * The directory a `workspace.members` entry names, one reference per path segment, relative to the directory of the
 * manifest declaring it.
 *
 * Being file references, moving or renaming any directory along the path rewrites the entry, and every segment
 * navigates to its directory. Completion only offers directories leading to a nested manifest, rather than every
 * directory below the root.
 */
class ElideMemberPathReferenceSet(element: PklStringContent) : FileReferenceSet(
  /* str = */ element.text,
  /* element = */ element,
  /* startInElement = */ 0,
  /* provider = */ null,
  /* caseSensitive = */ SystemInfo.isFileSystemCaseSensitive,
  /* endingSlashNotAllowed = */ false,
) {
  override fun isAbsolutePathReference(): Boolean = false

  override fun computeDefaultContexts(): Collection<PsiFileSystemItem> {
    val directory = element.containingFile.originalFile.virtualFile?.parent ?: return emptyList()
    return listOfNotNull(PsiManager.getInstance(element.project).findDirectory(directory))
  }

  override fun createFileReference(range: TextRange, index: Int, text: String): FileReference {
    return ElideMemberPathReference(this, range, index, text)
  }
}

/** One segment of a `workspace.members` entry. */
class ElideMemberPathReference(
  referenceSet: ElideMemberPathReferenceSet,
  range: TextRange,
  index: Int,
  text: String,
) : FileReference(referenceSet, range, index, text) {
  /**
   * Offers the remainder of the path to every nested manifest not yet declared, starting at this segment: under
   * `libs/`, the candidate `libs/core` is offered as `core`, and at the first segment as `libs/core` whole.
   */
  override fun getVariants(): Array<Any> {
    val manifest = element.containingFile.originalFile as? PklModule ?: return emptyArray()
    val root = manifest.virtualFile?.parent ?: return emptyArray()

    // read from the file being completed: its own entry carries the completion placeholder, so it never matches a
    // candidate, while an entry declared elsewhere in the list does
    val declared = (element.containingFile as? PklModule)?.declaredMembers().orEmpty()
      .mapTo(HashSet()) { (path, _) -> normalizeMemberPath(path) }

    val typed = element.text.substring(0, rangeInElement.startOffset)

    return discoverMemberCandidates(manifest, root).asSequence()
      .filter { it.path !in declared && it.path.startsWith(typed) }
      .map { candidate ->
        LookupElementBuilder.create(candidate.path.removePrefix(typed))
          .withIcon(Constants.Icons.ELIDE)
          .withTypeText(candidate.project.name, true)
      }
      .toList()
      .toTypedArray()
  }
}

// -- Projects

/**
 * The workspace project a `project("…")` call (or the `project` of a `ProjectArtifact`) names, resolving to that
 * project's manifest.
 */
class ElideProjectNameReference(element: PklStringContent) :
  PsiReferenceBase<PklStringContent>(element, TextRange(0, element.textLength)),
  HighlightedReference {
  /** Name of the project referenced. */
  val projectName: String get() = referenceText(element)

  override fun resolve(): PsiElement? = element.containingFile.manifestWorkspace()?.project(projectName)?.manifest

  override fun getVariants(): Array<Any> {
    val workspace = element.containingFile.manifestWorkspace() ?: return emptyArray()
    val self = element.containingFile.originalFile.virtualFile

    // a project never consumes its own artifacts through a reference
    return workspace.projects.asSequence()
      .filter { it.manifest.virtualFile != self }
      .map { project ->
        val path = VfsUtilCore.getRelativePath(project.directory, workspace.root.directory)?.ifEmpty { null }

        LookupElementBuilder.create(project.manifest, project.name)
          .withIcon(Constants.Icons.ELIDE)
          .withTypeText(path ?: Constants.Strings["elide.manifest.workspace.root"], true)
      }
      .toList()
      .toTypedArray()
  }

  /**
   * Keeps the name as written: the target is the project's manifest, whose file name has nothing to do with the name
   * the project is known by.
   */
  override fun handleElementRename(newElementName: String): PsiElement = element
}

// -- Artifacts

/**
 * The name of an artifact some manifest of the workspace declares, resolving to the entry declaring it.
 *
 * Implementations pick the manifest the name is looked up in.
 */
sealed class ElideArtifactReference(element: PklStringContent) :
  PsiReferenceBase<PklStringContent>(element, TextRange(0, element.textLength)),
  HighlightedReference {
  /** Name of the artifact referenced. */
  val artifactName: String get() = referenceText(element)

  /** The project the name is looked up in, or `null` when it cannot be determined. */
  abstract fun targetProject(): ManifestProject?

  /** Whether the artifact declared under [name] is offered in completion. */
  protected open fun isCandidate(name: String): Boolean = true

  override fun resolve(): PsiElement? = targetProject()?.artifacts?.get(artifactName)

  override fun getVariants(): Array<Any> {
    val project = targetProject() ?: return emptyArray()

    return project.artifacts.asSequence()
      .filter { (name, _) -> isCandidate(name) }
      .map { (name, entry) -> lookup(name, entry, project) }
      .toList()
      .toTypedArray()
  }

  protected open fun lookup(name: String, entry: PklObjectEntry, project: ManifestProject): LookupElementBuilder {
    return LookupElementBuilder.create(entry, name)
      .withIcon(Constants.Icons.ELIDE)
      .withTypeText(entry.artifactKind, true)
  }

  override fun handleElementRename(newElementName: String): PsiElement = element
}

/** The artifact of another workspace project that a `.artifact("…")` call (or a `ProjectArtifact`) names. */
class ElideProjectArtifactReference(
  element: PklStringContent,
  /** Name of the project declaring the artifact, or `null` when the reference does not spell it out. */
  val projectName: String?,
) : ElideArtifactReference(element) {
  override fun targetProject(): ManifestProject? {
    return projectName?.let { element.containingFile.manifestWorkspace()?.project(it) }
  }

  override fun lookup(name: String, entry: PklObjectEntry, project: ManifestProject): LookupElementBuilder {
    return super.lookup(name, entry, project).withTailText(" (${project.name})", true)
  }
}

/** An artifact of the same manifest, named by the `dependsOn` or `from` list of another artifact's declaration. */
class ElideLocalArtifactReference(
  element: PklStringContent,
  /** The entry declaring the artifact whose list holds this name. */
  private val declaration: PklObjectEntry,
) : ElideArtifactReference(element) {
  override fun targetProject(): ManifestProject? {
    // completion reads the copy it runs on, so artifacts declared by the edit in progress are offered as well
    val manifest = element.containingFile as? PklModule ?: return null
    val directory = manifest.originalFile.virtualFile?.parent ?: return null

    return ManifestProject(manifest.declaredProjectName() ?: directory.name, directory, manifest)
  }

  // an artifact never depends on itself
  override fun isCandidate(name: String): Boolean = name != declaration.artifactName()
}
