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
package dev.elide.intellij.execution.coverage

import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration

/**
 * Coverage settings of a single Elide run configuration.
 *
 * The runner is fixed: the CLI decides how coverage is collected, and the plugin always merges what it produces into
 * one LCOV report, so there is no choice of runner to offer.
 */
class ElideCoverageEnabledConfiguration(
  configuration: RunConfigurationBase<*>
) : CoverageEnabledConfiguration(configuration, ElideCoverageRunner.instance)
