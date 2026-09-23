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

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import dev.elide.intellij.Constants
import org.pkl.intellij.psi.*

/**
 * One project of a workspace, as the manifest in [directory] declares it.
 *
 * Everything here is read from the manifest's PSI rather than from the synced model, so editing features built on it
 * answer before the first sync and follow unsaved edits: a member added to the root is completable in its siblings as
 * soon as it is typed.
 *
 * @param name The name Elide knows the project by: the one its manifest declares, else its directory's.
 * @param directory Directory holding the manifest.
 * @param manifest The project's manifest.
 */
data class ManifestProject(val name: String, val directory: VirtualFile, val manifest: PklModule) {
  /** The artifacts this project declares, keyed by the name they are declared under, in declaration order. */
  val artifacts: Map<String, PklObjectEntry> get() = manifest.declaredArtifacts()
}

/**
 * A workspace as its manifests declare it: the [root] declaring `workspace.members`, and the [members] it declares
 * which hold a manifest. A standalone project is a workspace of one.
 *
 * Elide workspaces are exactly two layers deep — a member's own `workspace` block is never composed with its
 * root's — so the root is the only manifest whose members are read.
 */
class ManifestWorkspace(val root: ManifestProject, val members: List<ManifestProject>) {
  /** Every project of the workspace, the root first and the members in declaration order. */
  val projects: List<ManifestProject> get() = listOf(root) + members

  /** Whether this is a multi-project workspace, rather than a standalone project. */
  val isWorkspace: Boolean get() = members.isNotEmpty()

  /** Returns the project Elide knows by [name], root included, or `null` when the workspace holds none. */
  fun project(name: String): ManifestProject? = projects.find { it.name == name }
}

/**
 * Returns the workspace the manifest containing this element belongs to, or `null` when the element is in no
 * manifest. A manifest no root claims as a member is a workspace of its own.
 *
 * The answer is cached on the manifest until any PSI or VFS structure changes, which covers edits to every manifest it
 * was read from: completion and highlighting ask for it once per reference.
 */
fun PsiFile.manifestWorkspace(): ManifestWorkspace? {
  // completion runs on a copy of the file, which has no virtual file of its own
  val manifest = originalFile as? PklModule ?: return null
  if (manifest.virtualFile?.name != Constants.MANIFEST_NAME) return null

  return CachedValuesManager.getCachedValue(manifest) {
    CachedValueProvider.Result.create(
      computeWorkspace(manifest),
      PsiModificationTracker.MODIFICATION_COUNT,
      VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
    )
  }
}

private fun computeWorkspace(manifest: PklModule): ManifestWorkspace? {
  val self = manifest.asManifestProject() ?: return null

  // a root is the answer for itself; otherwise the root is the nearest ancestor manifest listing this directory
  if (manifest.declaredMembers().isNotEmpty()) return workspaceOf(self)

  var ancestor = self.directory.parent
  while (ancestor != null) {
    val candidate = ancestor.findChild(Constants.MANIFEST_NAME)
      ?.let { PsiManager.getInstance(manifest.project).findFile(it) as? PklModule }
      ?.asManifestProject()

    if (candidate != null) {
      val workspace = workspaceOf(candidate)
      if (workspace.members.any { it.directory == self.directory }) return workspace
    }

    ancestor = ancestor.parent
  }

  return ManifestWorkspace(self, emptyList())
}

private fun workspaceOf(root: ManifestProject): ManifestWorkspace {
  val psiManager = PsiManager.getInstance(root.manifest.project)

  val members = root.manifest.declaredMembers().mapNotNull { (path, _) ->
    val directory = root.directory.findFileByRelativePath(path)?.takeIf { it.isDirectory } ?: return@mapNotNull null
    val manifest = directory.findChild(Constants.MANIFEST_NAME) ?: return@mapNotNull null

    (psiManager.findFile(manifest) as? PklModule)?.asManifestProject()
  }

  // the same directory may be listed twice under different spellings (`core`, `./core`); Elide rejects that, and it
  // is still a single project here
  return ManifestWorkspace(root, members.distinctBy { it.directory }.filter { it.directory != root.directory })
}

private fun PklModule.asManifestProject(): ManifestProject? {
  val directory = virtualFile?.parent ?: return null
  return ManifestProject(name = declaredProjectName() ?: directory.name, directory = directory, manifest = this)
}

/**
 * A manifest nested below a workspace root, which the root could declare as a member.
 *
 * @param path Directory of the manifest, relative to the root's, `/`-separated.
 * @param project The project the manifest describes.
 */
data class MemberCandidate(val path: String, val project: ManifestProject)

/**
 * Returns the manifests found below [root] that could be declared as its members: every nested `elide.pkl` the IDE
 * indexes, sorted by path.
 *
 * Workspaces do not nest, so a nested workspace root is left out along with every manifest below it: those belong to
 * that other workspace. Returns an empty list while indices are unavailable.
 */
fun discoverMemberCandidates(manifest: PklModule, root: VirtualFile): List<MemberCandidate> {
  val project = manifest.project
  if (DumbService.isDumb(project)) return emptyList()

  val psiManager = PsiManager.getInstance(project)

  val nested = FilenameIndex.getVirtualFilesByName(Constants.MANIFEST_NAME, GlobalSearchScope.projectScope(project))
    .filter { file -> file.parent.let { it != null && it != root && VfsUtilCore.isAncestor(root, it, true) } }
    .mapNotNull { file -> psiManager.findFile(file) as? PklModule }

  val nestedRoots = nested.filter { it.declaredMembers().isNotEmpty() }.mapNotNull { it.virtualFile?.parent }

  return nested.asSequence()
    .mapNotNull { module -> module.asManifestProject() }
    .filter { candidate -> nestedRoots.none { VfsUtilCore.isAncestor(it, candidate.directory, false) } }
    .mapNotNull { candidate ->
      VfsUtilCore.getRelativePath(candidate.directory, root)?.let { MemberCandidate(it, candidate) }
    }
    .sortedBy { it.path }
    .toList()
}

/**
 * Returns [path], a `workspace.members` entry, in the spelling [MemberCandidate.path] uses: without `./` segments or
 * a trailing separator, so declared entries and discovered candidates compare equal.
 */
fun normalizeMemberPath(path: String): String {
  return path.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
}

// -- Manifest declarations

/** Returns the module-level property named [name] of this manifest, or `null` when it declares none. */
fun PklModule.moduleProperty(name: String): PklClassProperty? = properties.find { it.propertyName.textMatches(name) }

/** The `name` this manifest declares, or `null` when it names none (or not as a constant string). */
fun PklModule.declaredProjectName(): String? = moduleProperty("name")?.expr?.stringValue()

/**
 * The paths this manifest declares under `workspace.members`, paired with the literal declaring each. Entries that are
 * no constant string are skipped.
 */
fun PklModule.declaredMembers(): List<Pair<String, PklStringLiteral>> {
  val workspace = moduleProperty("workspace") ?: return emptyList()

  return workspace.objectBodies()
    .flatMap { it.properties.filter { property -> property.propertyName.textMatches("members") } }
    .flatMap { it.objectBodies() }
    .flatMap { body -> body.members.filterIsInstance<PklObjectElement>() }
    .mapNotNull { element ->
      val literal = element.expr as? PklStringLiteral ?: return@mapNotNull null
      literal.content.escapedText()?.let { it to literal }
    }
    .toList()
}

/**
 * The artifacts this manifest declares under the module-level `artifacts` mapping, keyed by the name they are declared
 * under, in declaration order.
 */
fun PklModule.declaredArtifacts(): Map<String, PklObjectEntry> {
  val artifacts = moduleProperty("artifacts") ?: return emptyMap()

  return artifacts.objectBodies()
    .flatMap { body -> body.members.filterIsInstance<PklObjectEntry>() }
    .mapNotNull { entry -> entry.artifactName()?.let { it to entry } }
    .toMap()
}

/** The name an `artifacts` entry is declared under: a string key, or a property holding one. */
fun PklObjectEntry.artifactName(): String? = when (val key = keyExpr) {
  is PklStringLiteral -> key.content.escapedText()
  is PklUnqualifiedAccessExpr -> key.takeIf { it.argumentList == null }?.binding()?.stringValue()
  else -> null
}

/** Simple name of the class an `artifacts` entry instantiates (`Jar`, `NativeImage`…), or `null` if it names none. */
val PklObjectEntry.artifactKind: String?
  get() = ((valueExpr as? PklNewExpr)?.type as? PklDeclaredType)?.typeName?.simpleName?.identifier?.text

/**
 * Every object body defining the value of this property: the bodies amending it in place (`x { … }`) and the body of
 * an object it is assigned (`x = new { … }`, `x = y { … }`).
 */
fun PklProperty.objectBodies(): Sequence<PklObjectBody> {
  val assigned = (expr as? PklObjectBodyOwner)?.objectBody
  return objectBodyList.asSequence() + listOfNotNull(assigned)
}

/** The value of this expression when it is a constant string literal, or `null` otherwise. */
fun PklExpr.stringValue(): String? = (this as? PklStringLiteral)?.content?.escapedText()
