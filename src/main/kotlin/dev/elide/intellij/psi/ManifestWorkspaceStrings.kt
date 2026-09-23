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

import com.intellij.psi.PsiElement
import org.pkl.intellij.psi.*

/**
 * A string of a manifest naming something of its workspace: a member directory, a project, or an artifact.
 *
 * Strings are recognized by their syntactic position alone. The Pkl plugin cannot resolve the `elide:` modules a
 * manifest amends, so no type information is available to tell a `ProjectArtifact` apart from any other value; the
 * shapes below are the ones the schema documents, which is also what the CLI's own diagnostics refer to.
 */
sealed interface ManifestWorkspaceString {
  /** A directory under `workspace.members`, relative to the manifest declaring it. */
  data object MemberPath : ManifestWorkspaceString

  /** The name of a workspace project: `project("core")`, or `project = "core"` in a `ProjectArtifact`. */
  data object ProjectName : ManifestWorkspaceString

  /**
   * The name of an artifact another project declares: `project("core").artifact("fat")` (the receiver possibly bound
   * to a `local`), or `artifact = "fat"` in a `ProjectArtifact`.
   *
   * @param project Name of the project declaring the artifact, or `null` when the reference does not spell it out.
   */
  data class ProjectArtifactName(val project: String?) : ManifestWorkspaceString

  /**
   * The name of an artifact of the same manifest, in the `dependsOn` or `from` list of an artifact declaration.
   *
   * @param declaration The entry declaring the artifact the list belongs to.
   */
  data class LocalArtifactName(val declaration: PklObjectEntry) : ManifestWorkspaceString
}

/** Returns what this string content names in the workspace, or `null` when it is no workspace reference. */
fun PklStringContent.workspaceString(): ManifestWorkspaceString? {
  val literal = parent as? PklStringLiteral ?: return null

  return when (val holder = literal.parent) {
    is PklArgumentList -> callArgumentString(literal, holder)
    is PklObjectProperty -> propertyValueString(literal, holder)
    is PklObjectElement -> listElementString(holder)
    else -> null
  }
}

/** `project("…")`, `module.project("…")`, and `.artifact("…")` on a project reference. */
private fun callArgumentString(literal: PklStringLiteral, arguments: PklArgumentList): ManifestWorkspaceString? {
  if (arguments.elements.firstOrNull() != literal) return null
  val call = arguments.parent as? PklAccessExpr ?: return null

  return when {
    call.isProjectCall() -> ManifestWorkspaceString.ProjectName

    call is PklQualifiedAccessExpr && call.memberNameText == ARTIFACT_MEMBER ->
      // any other `.artifact(…)` call is left alone: only a receiver that is a project reference names a project
      call.receiverExpr.referencedProjectName()?.let { ManifestWorkspaceString.ProjectArtifactName(it) }

    else -> null
  }
}

/** `project = "…"` and `artifact = "…"` inside `new ProjectArtifact { … }`, or amending a project reference. */
private fun propertyValueString(literal: PklStringLiteral, property: PklObjectProperty): ManifestWorkspaceString? {
  if (property.expr != literal) return null
  val body = property.parent as? PklObjectBody ?: return null

  val project = when (val owner = body.parent) {
    is PklNewExpr -> {
      if (owner.declaredTypeName() != PROJECT_ARTIFACT_TYPE) return null
      body.getConstantStringProperty(PROJECT_MEMBER)
    }

    // `(core) { artifact = "fat" }` narrows the reference the way `.artifact("fat")` does
    is PklAmendExpr -> owner.parentExpr.referencedProjectName() ?: return null
    else -> return null
  }

  return when {
    property.propertyName.textMatches(PROJECT_MEMBER) && body.parent is PklNewExpr ->
      ManifestWorkspaceString.ProjectName
    property.propertyName.textMatches(ARTIFACT_MEMBER) -> ManifestWorkspaceString.ProjectArtifactName(project)
    else -> null
  }
}

/** Elements of `workspace.members`, and of the `dependsOn`/`from` lists of an artifact declaration. */
private fun listElementString(element: PklObjectElement): ManifestWorkspaceString? {
  val list = (element.parent as? PklObjectBody)?.owningProperty() ?: return null

  if (list.propertyName.textMatches(MEMBERS_PROPERTY)) {
    val workspace = (list.parent as? PklObjectBody)?.owningProperty() ?: return null
    return ManifestWorkspaceString.MemberPath.takeIf { workspace.isModuleProperty(WORKSPACE_PROPERTY) }
  }

  if (ARTIFACT_LIST_PROPERTIES.none { list.propertyName.textMatches(it) }) return null

  // the list has to belong to an artifact declaration: `dependsOn` also lists source sets in a `sources` entry
  val declaration = (list.parent as? PklObjectBody)?.owningEntry() ?: return null
  val artifacts = (declaration.parent as? PklObjectBody)?.owningProperty() ?: return null

  return ManifestWorkspaceString.LocalArtifactName(declaration)
    .takeIf { artifacts.isModuleProperty(ARTIFACTS_PROPERTY) }
}

/**
 * Returns the name of the project this expression references, or `null` when it is no project reference or does not
 * spell the name out as a constant.
 *
 * Accepts `project("core")` and `module.project("core")`, the same narrowed with `.artifact(…)` or amended, a
 * `new ProjectArtifact { project = "core" }`, and a property (typically a module-level `local`) bound to any of those.
 */
fun PklExpr.referencedProjectName(): String? = referencedProjectName(depth = 0)

private fun PklExpr.referencedProjectName(depth: Int): String? {
  // a property bound to itself (or a cycle of them) is a manifest error, and must not overflow the stack here
  if (depth > MAX_REFERENCE_DEPTH) return null

  return when (this) {
    is PklParenthesizedExpr -> expr?.referencedProjectName(depth + 1)
    is PklAmendExpr -> parentExpr.referencedProjectName(depth + 1)
    is PklNewExpr -> objectBody?.takeIf { declaredTypeName() == PROJECT_ARTIFACT_TYPE }
      ?.getConstantStringProperty(PROJECT_MEMBER)

    is PklAccessExpr -> when {
      isProjectCall() -> argumentList?.elements?.firstOrNull()?.stringValue()
      this is PklQualifiedAccessExpr && memberNameText == ARTIFACT_MEMBER ->
        receiverExpr.referencedProjectName(depth + 1)
      this is PklUnqualifiedAccessExpr && argumentList == null -> binding()?.referencedProjectName(depth + 1)

      else -> null
    }

    else -> null
  }
}

/**
 * Returns the expression the property this access names is bound to, looked up lexically: the innermost object body
 * declaring a property of that name, else the module.
 *
 * Lexical scope is all a binding such as `local core = project("core")` needs, and it is what Pkl resolves an
 * unqualified access to before considering the members a module inherits. Going through the Pkl plugin's resolver
 * instead would evaluate the types of the amended `elide:` modules, which it cannot locate, on every keystroke.
 */
internal fun PklUnqualifiedAccessExpr.binding(): PklExpr? {
  val name = memberNameText
  var scope: PsiElement? = parent

  while (scope != null) {
    val properties = when (scope) {
      is PklObjectBody -> scope.properties
      is PklModule -> scope.properties
      else -> null
    }

    properties?.find { it.propertyName.textMatches(name) }?.let { return it.expr }
    scope = scope.parent
  }

  return null
}

/** Whether this is a call of the manifest's `project` function, unqualified or through `module`. */
private fun PklAccessExpr.isProjectCall(): Boolean {
  if (memberNameText != PROJECT_MEMBER || argumentList == null) return false

  return when (this) {
    is PklUnqualifiedAccessExpr -> true
    is PklQualifiedAccessExpr -> receiverExpr is PklModuleExpr
    else -> false
  }
}

/** The property this body defines the value of, amended in place or assigned. */
private fun PklObjectBody.owningProperty(): PklProperty? = when (val owner = parent) {
  is PklProperty -> owner
  is PklObjectBodyOwner -> owner.parent as? PklProperty
  else -> null
}

/** The mapping entry this body defines the value of, amended in place or assigned. */
private fun PklObjectBody.owningEntry(): PklObjectEntry? = when (val owner = parent) {
  is PklObjectEntry -> owner
  is PklObjectBodyOwner -> owner.parent as? PklObjectEntry
  else -> null
}

private fun PklProperty.isModuleProperty(name: String): Boolean {
  return this is PklClassProperty && parent is PklModuleMemberList && propertyName.textMatches(name)
}

private fun PklNewExpr.declaredTypeName(): String? {
  return (type as? PklDeclaredType)?.typeName?.simpleName?.identifier?.text
}

private const val PROJECT_MEMBER = "project"
private const val ARTIFACT_MEMBER = "artifact"
private const val PROJECT_ARTIFACT_TYPE = "ProjectArtifact"
private const val WORKSPACE_PROPERTY = "workspace"
private const val MEMBERS_PROPERTY = "members"
private const val ARTIFACTS_PROPERTY = "artifacts"
private val ARTIFACT_LIST_PROPERTIES = listOf("dependsOn", "from")

/** How many property bindings a project reference is followed through. */
private const val MAX_REFERENCE_DEPTH = 8
