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
package dev.elide.intellij.execution

import com.intellij.debugger.engine.DelayedRemoteConnection
import com.intellij.debugger.engine.DelayedRemoteConnectionImpl
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RemoteConnection
import com.intellij.execution.configurations.RemoteConnectionCreator
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunnableState
import com.intellij.openapi.project.Project
import dev.elide.intellij.Constants
import dev.elide.intellij.cli.ElideCli
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Task state used for debug runs of an [ElideRunConfiguration], driven by [ElideDebugRunner].
 *
 * The command line starts a suspended JVM, or an Android debug target forwarding JDWP to a local socket.
 * The attach is deferred until this run announces its own listening port, rather than dialing a stale debuggee
 * left by another run. Build targets may choose a free port; only default run/test launches use the 5005 preflight.
 */
internal class ElideDebugRunnableState(
  settings: ExternalSystemTaskExecutionSettings,
  project: Project,
  configuration: ElideRunConfiguration,
  private val environment: ExecutionEnvironment,
) : ExternalSystemRunnableState(
  /* settings = */ settings,
  /* project = */ project,
  /* debug = */ false,
  /* configuration = */ configuration,
  /* env = */ environment,
),
  RemoteConnectionCreator {
  private val probeDefaultPort = ElideCli.parse(settings.taskNames).let { invocation ->
    invocation.command != ElideCli.BUILD &&
      settings.taskNames.getOrNull(ElideCli.flagIndex(settings.taskNames, invocation, ElideCli.DEBUGGER)) ==
      ElideCli.DEBUGGER.option
  }

  private val connection = DelayedRemoteConnectionImpl(
    /* useSockets = */ true,
    /* hostName = */ Constants.DEBUGGER_HOST,
    /* address = */ Constants.DEBUGGER_PORT.toString(),
    /* serverMode = */ false,
  )

  override fun createRemoteConnection(environment: ExecutionEnvironment): RemoteConnection = connection

  /** The debuggee is listening by the time the attach is triggered, so the connection is dialled once. */
  override fun isPollConnection(): Boolean = false

  override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult? {
    if (probeDefaultPort) checkDebuggerPortFree()

    // read by `ExternalSystemRunnableState.execute` below, which registers the listener for the task it starts, so
    // this run's output reaches the listener whichever console claims it: a test run's output is handed to
    // `ElideTestsExecutionConsoleManager` instead of the process handler, and never reaches a process listener
    environment.putUserData(ExternalSystemRunnableState.TASK_NOTIFICATION_LISTENER_KEY, JdwpBannerListener())

    return super.execute(executor, runner)
  }

  /**
   * Fails the run when a JDWP server already answers on [Constants.DEBUGGER_PORT], before the CLI is started.
   *
   * The default run/test agent binds this port, so a debuggee already parked on it makes this run undebuggable.
   * Build targets and explicitly addressed debugger launches own their endpoint and skip this probe.
   *
   * The port is dialled rather than bound. A bind answers a different question on every platform this runs on: the
   * socket the previous debug session left in `TIME_WAIT` refuses one for minutes after that session ended, and
   * macOS grants one for a port another process is already listening on. Dialling it asks exactly what the debugger
   * is about to ask, and a JDWP agent survives the handshake that never comes: it keeps listening for the real one.
   */
  private fun checkDebuggerPortFree() {
    val address = InetSocketAddress(Constants.DEBUGGER_HOST, Constants.DEBUGGER_PORT)
    val answered = try {
      Socket().use { it.connect(address, PROBE_TIMEOUT_MILLIS) }
      true
    } catch (_: IOException) {
      false
    }

    if (!answered) return

    // the port goes in as text: a number argument would be grouped by the message format ("5,005")
    val port = Constants.DEBUGGER_PORT.toString()
    throw ExecutionException(Constants.Strings["execution.error.debuggerPortInUse", port])
  }

  /** Attaches the debugger to the JDWP server this run's agent announces, once. */
  private inner class JdwpBannerListener : ExternalSystemTaskNotificationListener {
    private val announced = AtomicBoolean(false)
    private val pending = mutableMapOf<ProcessOutputType, StringBuilder>()

    override fun onTaskOutput(id: ExternalSystemTaskId, text: String, outputType: ProcessOutputType) {
      if (announced.get()) return
      // External task output is chunked, not line-oriented. Never attach to a partial port token.
      synchronized(pending) {
        val buffer = pending.getOrPut(outputType) { StringBuilder() }
        buffer.append(text)
        var newline = buffer.indexOf("\n")
        while (newline >= 0) {
          val port = announcedPort(buffer.substring(0, newline))
          buffer.delete(0, newline + 1)
          if (port != null && announced.compareAndSet(false, true)) {
            connection.debuggerAddress = port
            connection.applicationAddress = port
            scheduleAttach(attempt = 0)
            return
          }
          newline = buffer.indexOf("\n")
        }
      }
    }

    /**
     * Runs the attach the debugger left behind on [DelayedRemoteConnection], on the EDT it documents as its own.
     *
     * The banner can arrive before the debugger has installed that attach: the run is already streaming output by
     * the time `execute` returns, and the attach is installed after it. Whatever the debugger is doing is on the
     * EDT as well, so re-posting is what waits for it; the retries bound a run that announced a port under a
     * debugger that never came (a session cancelled as it started, say).
     */
    private fun scheduleAttach(attempt: Int) {
      ApplicationManager.getApplication().invokeLater({
        val attach = connection.attachRunnable
        when {
          attach != null -> attach.run()
          attempt < ATTACH_ATTEMPTS -> scheduleAttach(attempt + 1)
        }
      }, ModalityState.any())
    }
  }

  internal companion object {
    /** Time the port probe waits for an answer; the debuggee it looks for is on this machine. */
    private const val PROBE_TIMEOUT_MILLIS = 500

    /** Times the attach is re-posted while the debugger installs it. */
    private const val ATTACH_ATTEMPTS = 3

    /** Transport the JDWP agent names in its banner; used to keep the regex off every other output line. */
    private const val TRANSPORT_NAME = "dt_socket"

    /** Both JVM agents and Android adb-forward targets emit this banner, possibly wrapped in a TAP comment. */
    internal fun announcedPort(line: String): String? {
      if (TRANSPORT_NAME !in line) return null
      val port = JDWP_BANNER.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
      return port.takeIf { it in 1..65535 }?.toString()
    }

    /**
     * Banner the JDWP agent prints once its transport is listening, carrying the port it bound.
     *
     * A test run's output reaches the IDE as TAP, where the line arrives as the `# out: …` comment the CLI wraps the
     * debuggee's output in, so the banner is matched anywhere in the line rather than anchored to its start.
     */
    private val JDWP_BANNER = Regex("""Listening for transport dt_socket at address:\s*(\d+)""")
  }
}
