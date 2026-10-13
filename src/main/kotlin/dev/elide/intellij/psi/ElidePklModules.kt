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

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import dev.elide.intellij.Constants
import org.pkl.intellij.PklStandaloneFilesExtension
import org.pkl.intellij.packages.dto.PklProject
import org.pkl.intellij.psi.PklModuleResolverExtension

/**
 * Resolves `elide:` module URIs (`amends "elide:project.pkl"`, `import "elide:Coverage.pkl"`) to the schema bundled
 * with the plugin under `elide/pkl/`, the same modules the CLI evaluates the manifest against. Relative imports
 * between those modules then resolve inside the plugin JAR.
 */
class ElidePklModuleResolver : PklModuleResolverExtension {
  override fun resolveModuleFile(uri: String, project: Project, context: PklProject?): VirtualFile? = find(uri)

  companion object {
    private const val SCHEME = "elide:"
    private const val ROOT = "elide/pkl/"

    /** The bundled module for an `elide:` [uri], or null for another scheme or a name outside the schema. */
    fun find(uri: String): VirtualFile? {
      if (!uri.startsWith(SCHEME)) return null
      val name = uri.removePrefix(SCHEME).trimStart('/')
      if (!name.endsWith(".pkl") || name.contains('/') || name.contains('\\')) return null
      val url = ElidePklModuleResolver::class.java.classLoader.getResource(ROOT + name) ?: return null
      return VfsUtil.findFileByURL(url)
    }
  }
}

/**
 * Marks Elide manifests as standalone Pkl files: Elide evaluates them, not the Pkl CLI, and they belong to no Pkl
 * project, so the Pkl plugin's "Pkl CLI not found" and "Sync Pkl project" banners do not apply to them.
 */
class ElideManifestStandaloneFiles : PklStandaloneFilesExtension {
  override fun isStandaloneFile(file: PsiFile): Boolean = file.name == Constants.MANIFEST_NAME
}
