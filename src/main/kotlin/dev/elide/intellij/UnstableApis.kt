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

/**
 * Inventory of the experimental (`@ApiStatus.Experimental` / `@ApiStatus.Internal`) platform APIs the plugin relies
 * on, and the classes carrying the corresponding `@Suppress("UnstableApiUsage")`.
 *
 * Every entry must be re-verified when the supported build range changes (see `intellij.sinceBuild` /
 * `intellij.untilBuild` in `gradle/libs.versions.toml`, currently `251` – `261.*`); the platform is free to change
 * these signatures in any release inside that range.
 *
 * | API | Used by |
 * |---|---|
 * | `AbstractOpenProjectProvider` | [dev.elide.intellij.project.ElideOpenProjectProvider] |
 * | `ExternalSystemTrustedProjectDialog.confirmLinkingUntrustedProjectAsync` | [dev.elide.intellij.project.ElideOpenProjectProvider] |
 * | `ExternalSystemUnlinkedProjectAware` | [dev.elide.intellij.project.ElideUnlinkedProjectAware] |
 * | `ExternalSystemProjectResolver.resolveProjectInfo(…, ProjectResolverPolicy, …)` | [dev.elide.intellij.project.ElideProjectResolver] |
 * | `AbstractExternalProjectSettingsControl` | [dev.elide.intellij.settings.ElideProjectSettingsControl] |
 * | `com.intellij.ui.layout.selectedValueIs` | [dev.elide.intellij.settings.ElideProjectSettingsControl] |
 * | `KotlinMainFunctionDetector` / `findMainOwner` / `getMainClassJvmName` | [dev.elide.intellij.execution.ElideJvmMainConfigurationProducer] |
 * | `ExternalSystemReifiedRunConfigurationExtension` + command line/working directory fragments | [dev.elide.intellij.execution.ElideRunConfigurationExtension], [dev.elide.intellij.cli.ElideCommandLineInfo] |
 *
 * Known gap inside the supported range: `ExternalSystemUnlinkedProjectAware.getLinkedProjectsPaths` does not exist
 * before build 252, so [dev.elide.intellij.project.ElideUnlinkedProjectAware] implements it instead of inheriting
 * the default body — a Kotlin-generated bridge would reference a method missing on a 251 IDE.
 *
 * This object holds no code: it exists so the list lives in one reviewable place instead of being spread across the
 * suppressed classes.
 */
internal object UnstableApis
