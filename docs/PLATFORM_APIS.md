# Platform API usage

Inventory of the experimental, internal and deprecated IntelliJ Platform APIs the plugin calls, and what each one is
used for. Builds refer to the range declared by `intellij.sinceBuild` / `intellij.untilBuild` in
`gradle/libs.versions.toml` (251 to 262).

Every usage is reported by `./gradlew verifyPlugin` under
`build/reports/pluginVerifier/IU-<build>/plugins/dev.elide/<version>/`, in `internal-api-usages.txt`,
`experimental-api-usages.txt` and `deprecated-usages.txt`.

## Internal APIs

| API | Used by | Builds | Used for |
|---|---|---|---|
| `KotlinMainFunctionDetector`, `KotlinMainFunctionDetector.Companion.getInstanceDumbAware` | `ElideJvmMainConfigurationProducer.getEntryPointContainer` | 251-262 | Detecting whether a Kotlin file or class declares a runnable `main`, including `suspend fun main` and parameterless forms, when offering the gutter run/debug action |
| `KotlinRunConfigurationProducer.Companion.getMainClassJvmName` | `ElideJvmMainConfigurationProducer.setupConfigurationFromContext`, `isConfigurationFromContext`, `findExistingConfiguration` | 251-262 | Computing the JVM facade name (`MainKt`, `@JvmName` overrides) passed to the Elide CLI as the entrypoint |
| `ExternalSystemUnlinkedProjectAware.getLinkedProjectsPaths` | `ElideUnlinkedProjectAware` | 253-262 | Telling the IDE which Elide project paths are currently linked, so unlinked projects can be offered for import |

## Experimental APIs

| API | Used by | Builds | Used for |
|---|---|---|---|
| `AbstractOpenProjectProvider` (class, constructor, `systemId`, `isProjectFile`, `linkProject`) | `ElideOpenProjectProvider` | 251-262 | Recognising `elide.pkl` as a project file and linking its directory as an Elide external project |
| `AbstractOpenProjectProvider.linkToExistingProjectAsync` | `ElideUnlinkedProjectAware.linkAndLoadProjectAsync` | 251-262 | Linking and loading an Elide project from the unlinked project notification |
| `ExternalSystemProjectLinkListener` (interface, `onProjectLinked`, `onProjectUnlinked`) | `ElideUnlinkedProjectAware.subscribe` | 251-262 | Receiving link and unlink events for Elide projects |
| `ProjectResolverPolicy`, `ExternalSystemProjectResolver.resolveProjectInfo(…, ProjectResolverPolicy, …)` | `ElideProjectResolver.resolveProjectInfo` | 251-262 | Resolving the Elide manifest into the external system project model during sync |
| `com.intellij.openapi.progress.runBlockingCancellable` | `ElideProjectResolver.resolveProjectInfo`, `ElideTaskManager.executeTasks` | 251 only, stable from 252 | Running the suspending CLI calls of a sync or task under the IDE cancellation context |
| `Placeholder` (interface, `align`, `component`) | `ElideNewProjectWizardStep.setupUI`, `renderOptions` | 251-262 | Swapping the template option controls in the New Project wizard when a different template is selected |

## Deprecated APIs

| API | Used by | Used for |
|---|---|---|
| `ExternalSystemUtil.linkExternalProject` (positional overload) | `ElideOpenProjectProvider.linkProject`, `ElideProjectGenerator.generate` | Registering project settings and triggering the first sync; the `ImportSpec` overload only exists from build 252 |
| `ExternalSystemTaskNotificationListener.onTaskOutput(id, text, stdOut)` | `ElideProjectResolver.resolveProjectInfo`, `ElideTaskManager.executeTasks` | Streaming CLI output into the build tool window; the `ProcessOutputType` overload only exists from build 253 |
| `ExternalSystemUtil.refreshProject(project, systemId, path, isPreviewMode, progressExecutionMode)` | `ElideStartupActivity.ElideAutoLinkTracker` | Re-syncing a linked project when its Elide distribution setting changes |

## Signatures missing on part of the range

| Signature | Available | Handling in code |
|---|---|---|
| `ExternalSystemUnlinkedProjectAware.getLinkedProjectsPaths` | from 253 | `ElideUnlinkedProjectAware` implements the method, so no bridge referencing it is generated for 251 and 252 |
| `AbstractOpenProjectProvider.getProjectDirectory` (suspending form) | from 253; blocking form on 251 and 252 | `ElideOpenProjectProvider.linkProject` derives the project directory from the `VirtualFile` |

## Stable alternatives in use

`ElideProjectSettingsControl` builds the distribution path field from `TextFieldWithBrowseButton` plus
`installFileCompletionAndBrowseDialog` instead of the experimental `Row.textFieldWithBrowseButton` shorthand;
`ElideNewProjectWizardStep` builds its distribution field the same way.

`ElideNewProjectWizardStep` renders the template combo box with a plain `ListCellRenderer`:
`SimpleListCellRenderer.create` is scheduled for removal on 262, and its replacement
`com.intellij.ui.dsl.listCellRenderer.textListCellRenderer` is internal on 251.

`AbstractExternalProjectSettingsControl`, `ExternalSystemReifiedRunConfigurationExtension`, the run configuration
command line and working directory fragments, and `com.intellij.ui.layout.selectedValueIs` carry no stability
annotation on any supported build and need no suppression.
