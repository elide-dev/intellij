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

import com.intellij.openapi.project.Project
import java.util.Timer
import java.util.TimerTask
import org.pkl.intellij.packages.PklPackageService

/**
 * Cancels the package refresh the Pkl plugin's package service debounces on a [Timer] of its own, three seconds after
 * every PSI change in a Pkl file.
 *
 * The service's `dispose` cancels neither the pending task nor the timer, so a refresh still queued when a test's
 * project is disposed reads the indices of a dead project and throws `AlreadyDisposedException` on the timer thread.
 * Nothing catches it there: the JUnit 5 uncaught-exception check then fails whichever test happens to be running when
 * it fires, which is never the one that scheduled it.
 *
 * Tests editing Pkl PSI call this after every test, while their project is still alive. The service's fields are
 * private and it exposes no way to stop the timer, so they are read reflectively; a rename in the Pkl plugin fails
 * here loudly rather than bringing the flakiness back.
 */
fun cancelPklPackageRefresh(project: Project) {
  val service = project.getServiceIfCreated(PklPackageService::class.java) ?: return

  for (field in listOf("timerTask", "timer")) {
    when (val pending = service.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(service)) {
      is TimerTask -> pending.cancel()
      is Timer -> pending.cancel()
    }
  }
}
