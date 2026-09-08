# Platform API usage

Inventory of the experimental, internal and deprecated IntelliJ Platform APIs the plugin calls, and what each one is
used for. Builds refer to the range declared by `intellij.sinceBuild` / `intellij.untilBuild` in
`gradle/libs.versions.toml` (253 to 262). The plugin compiles against the build named by `intellij.target-ide` (261).

Every usage is reported by `./gradlew verifyPlugin` under
`build/reports/pluginVerifier/IU-<build>/plugins/dev.elide/<version>/`, in `internal-api-usages.txt`,
`experimental-api-usages.txt` and `deprecated-usages.txt`.

## Internal APIs

| API | Used by | Used for |
|---|---|---|
| `ExternalSystemUnlinkedProjectAware.getLinkedProjectsPaths` | `ElideUnlinkedProjectAware` | Telling the IDE which Elide project paths are currently linked, so unlinked projects can be offered for import. The interface declares the method with a body that throws, so an implementation is mandatory |
| `GeneralIdBasedToSMTRunnerEventsConvertor` (class, constructor) | `ElideTestsExecutionConsole.startSession` | Publishing the events decoded from the CLI's TAP stream into the test tree of an `elide test` run, addressed by the node ids TAP numbers a test's start and output with. The only public route to the same class is `OutputToGeneralTestEventsConverter`, which reaches it by way of TeamCity service-message text and would mean re-encoding every decoded event as a string for the platform to parse back |
| `StartBuildEventImpl` (class, constructor) | `ElideTestsExecutionConsoleManager.quietened` | Re-issuing an `elide test` run's start event to the Build window with a descriptor that does not raise that window on failure, since a failed test fails the build and the failures are already shown in the test tree. `StartBuildEvent.builder` is the public replacement and is not declared on 253 |

## Experimental APIs

| API | Used by | Used for |
|---|---|---|
| `AbstractOpenProjectProvider` (class, constructor, `systemId`, `isProjectFile`, `linkProject`) | `ElideOpenProjectProvider` | Recognising `elide.pkl` as a project file and linking its directory as an Elide external project |
| `AbstractOpenProjectProvider.linkToExistingProjectAsync` | `ElideUnlinkedProjectAware.linkAndLoadProjectAsync` | Linking and loading an Elide project from the unlinked project notification |
| `ExternalSystemProjectLinkListener` (interface, `onProjectLinked`, `onProjectUnlinked`) | `ElideUnlinkedProjectAware.subscribe` | Receiving link and unlink events for Elide projects |
| `ProjectResolverPolicy`, `ExternalSystemProjectResolver.resolveProjectInfo(…, ProjectResolverPolicy, …)` | `ElideProjectResolver.resolveProjectInfo` | Resolving the Elide manifest into the external system project model during sync |
| `Placeholder` (interface, `align`, `component`) | `ElideNewProjectWizardStep.setupUI`, `renderOptions` | Swapping the template option controls in the New Project wizard when a different template is selected |
| `BuildViewSettingsProvider` (interface, `isExecutionViewHidden`) | `ElideTestsExecutionConsole` | Yielding the run's build view to the test tree, so an `elide test` run shows results rather than a build log |
| `BuildProgressObservable.addListener` | `ElideTestsExecutionConsoleManager.forwardBuildEvents` | Subscribing to an `elide test` run's build events, so the events the test tree does not render are passed on to the Build window |

## Deprecated APIs

| API | Used by | Used for |
|---|---|---|
| `ExternalSystemAutoImportAware.getAffectedExternalProjectFiles` | `ElideAutoImportAware`, `ElideManager` | Listing the manifest and lockfile the IDE watches for auto-import. Scheduled for removal on 262, which replaces it with `getAffectedExternalProjectFilePaths`; both classes declare the replacement as well, without `override`, because the 261 compile target does not declare it yet. The JVM dispatches to the replacement on 262, so the deprecated method is only reached on 253 to 261 |
| `ExternalSystemUnlinkedProjectAware.linkAndLoadProject` | `ElideUnlinkedProjectAware` | Not called by the plugin. The interface declares the method as a Kotlin default with `DeprecationLevel.ERROR`, and Kotlin emits the bridge for it in every implementing class |

## Signatures missing on part of the range

| Signature | Available | Handling in code |
|---|---|---|
| `ExternalSystemAutoImportAware.getAffectedExternalProjectFilePaths` | from 262 | `ElideAutoImportAware` and `ElideManager` declare a matching method without `override` |
| `AbstractOpenProjectProvider.getProjectDirectory` | internal on the whole range | `ElideOpenProjectProvider.linkProject` derives the project directory from the `VirtualFile` |

## Stable alternatives in use

JVM `main` detection for the gutter run and debug actions is implemented in `dev.elide.intellij.psi`
(`findJvmMainClassName`, `findKotlinMainOwner`, `kotlinMainClassJvmName`, `findJavaMainClass`) over Java PSI, Kotlin
compiler PSI, `KotlinPsiHeuristics` and `ClassUtil.getJVMClassName`, instead of the internal
`KotlinMainFunctionDetector` and `KotlinRunConfigurationProducer.getMainClassJvmName`.

Project linking and re-sync use `ExternalSystemUtil.linkExternalProject(settings, ImportSpec)` and
`ExternalSystemUtil.refreshProject(path, ImportSpec)`; build output is streamed with
`ExternalSystemTaskNotificationListener.onTaskOutput(id, text, ProcessOutputType)`.

`ElideProjectSettingsControl` builds the distribution path field from `TextFieldWithBrowseButton` plus
`installFileCompletionAndBrowseDialog` instead of the experimental `Row.textFieldWithBrowseButton` shorthand;
`ElideNewProjectWizardStep` builds its distribution field the same way.

`ElideNewProjectWizardStep` renders the template combo box with a plain `ListCellRenderer`:
`SimpleListCellRenderer.create` is scheduled for removal on 262, and its replacement
`com.intellij.ui.dsl.listCellRenderer.textListCellRenderer` is internal on the whole range.

`AbstractExternalProjectSettingsControl`, `ExternalSystemReifiedRunConfigurationExtension`, the run configuration
command line and working directory fragments, and `com.intellij.ui.layout.selectedValueIs` carry no stability
annotation on any supported build and need no suppression.
