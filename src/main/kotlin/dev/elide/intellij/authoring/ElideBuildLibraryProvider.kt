package dev.elide.intellij.authoring

import com.intellij.lang.javascript.library.JSSyntheticLibraryProvider
import com.intellij.lang.javascript.psi.JSFile
import com.intellij.lang.javascript.psi.JSModuleStatusOwner.ModuleStatus
import com.intellij.lang.javascript.psi.ecma6.TypeScriptInterface
import com.intellij.lang.javascript.psi.ecma6.TypeScriptModule
import com.intellij.lang.javascript.psi.ecma6.TypeScriptTypeAlias
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.navigation.ItemPresentation
import java.nio.file.NoSuchFileException
import dev.elide.intellij.Constants
import javax.swing.Icon

/** Optional JavaScript integration; neither a tsconfig nor a project source file is ever generated. */
class ElideBuildLibraryProvider : AdditionalLibraryRootsProvider() {
  override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
    val applicable = CachedValuesManager.getManager(project).getCachedValue(project) {
      var manifest = false
      var ownDeclaration = false
      val factory = PsiFileFactory.getInstance(project)
      for (root in project.getBaseDirectories()) {
        VfsUtil.iterateChildrenRecursively(root, { it.name !in EXCLUDED_DIRECTORIES && !it.`is`(VFileProperty.SYMLINK) }) { file ->
          if (file.name == Constants.MANIFEST_NAME) manifest = true
          if (!file.isDirectory && file.extension in TYPESCRIPT_EXTENSIONS) {
            val text = try {
              FileDocumentManager.getInstance().getCachedDocument(file)?.immutableCharSequence
                ?: VfsUtilCore.loadText(file)
            } catch (_: NoSuchFileException) {
              // A before-delete VFS event can still expose an entry already removed from disk.
              return@iterateChildrenRecursively true
            }
            if ("ElideBuild" in text) {
              // Root providers run while the file index is being built. Physical PSI creation asks that same
              // index whether the file is excluded and recurses back here; detached PSI needs no file index.
              val psi = factory.createFileFromText(file.name, file.fileType, text) as? JSFile
              if (psi != null && declaresBuildGlobal(psi)) ownDeclaration = true
            }
          }
          !ownDeclaration
        }
        if (ownDeclaration) break
      }
      CachedValueProvider.Result.create(manifest && !ownDeclaration,
        PsiModificationTracker.MODIFICATION_COUNT, VirtualFileManager.getInstance())
    }
    if (!applicable) return emptyList()
    val resource = javaClass.getResource("/typescript/elide/build.d.ts") ?: return emptyList()
    val file = VfsUtil.findFileByURL(resource) ?: return emptyList()
    return listOf(BuildLibrary(file))
  }

  private class BuildLibrary(private val file: VirtualFile) : SyntheticLibrary(), JSSyntheticLibraryProvider, ItemPresentation {
    override fun getSourceRoots(): Collection<VirtualFile> = listOf(file)
    override fun equals(other: Any?): Boolean = other is BuildLibrary && other.file == file
    override fun hashCode(): Int = file.hashCode()
    override fun getPresentableText(): String = "Elide Build Extension API"
    override fun getLocationString(): String? = null
    override fun getIcon(unused: Boolean): Icon = Constants.Icons.ELIDE
  }

  companion object {
    private val EXCLUDED_DIRECTORIES = setOf(".git", ".idea", ".dev", "node_modules")
    private val TYPESCRIPT_EXTENSIONS = setOf("ts", "mts", "cts", "tsx")

    internal fun declaresBuildGlobal(file: JSFile): Boolean {
      // A declaration in a normal module is local. Only script globals and `declare global` augmentations win.
      val module = file.moduleStatus != ModuleStatus.NONE
      return PsiTreeUtil.collectElements(file) { element ->
        (element is TypeScriptInterface && element.name == "ElideBuild") ||
          (element is TypeScriptTypeAlias && element.name == "ElideBuild")
      }.any { declaration ->
        val enclosing = PsiTreeUtil.getParentOfType(declaration, TypeScriptModule::class.java)
        if (enclosing != null) enclosing.isGlobalScopeAugmentation else !module && declaration.parent == file
      }
    }
  }
}
