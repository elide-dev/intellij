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
package dev.elide.intellij.cli

/**
 * Schema of the Elide command line interface, as of Elide `1.5.1`.
 *
 * Everything the plugin needs to know about the shape of an `elide` invocation lives here: which commands exist, which
 * flags they accept, how those flags take their values, and which token of an argument vector names the command. The
 * completion tables ([ElideCliCompletion]), the "run anything" provider and the debugger integration all read this
 * model, so the CLI surface is described exactly once.
 *
 * Only the commands and flags that make sense from within the IDE are modelled. Interactive commands (`repl`), the
 * plugin's own concerns (`lsp`, `mcp`), and self-management commands (`init`, `upgrade`, `use`, `help`, `agent`) are
 * deliberately absent, as are hidden and no-op flags.
 *
 * The CLI exits with `0` on success, `1` when the program or test run fails, `2` on a usage error or an unresolvable
 * run target, and `124` when `--timeout` expires.
 */
object ElideCli {
  /** How a flag takes its value, mirroring the `num_args` and `require_equals` settings of the CLI's parser. */
  enum class FlagValue {
    /** The flag is a switch and never takes a value. */
    NONE,

    /** The flag requires a value, given either as `--flag value` or as `--flag=value`. */
    REQUIRED,

    /** The flag requires a value, which must be attached with `=`. */
    REQUIRED_EQUALS,

    /** The flag has a default and only accepts an explicitly attached `=value`. */
    OPTIONAL_EQUALS,
  }

  /**
   * A single command line flag.
   *
   * At least one of [long] and [short] is always present.
   *
   * @param long Name of the flag without its leading dashes, e.g. `test-name-pattern`.
   * @param short Single character form, if the CLI declares one.
   * @param value How the flag takes its value.
   * @param values Values the CLI enumerates for this flag; empty when the value is free-form.
   * @param repeatable Whether passing the flag more than once is meaningful.
   * @param descriptionKey Key of the flag's description in the plugin's string bundle.
   */
  data class Flag(
    val long: String? = null,
    val short: Char? = null,
    val value: FlagValue = FlagValue.NONE,
    val values: List<String> = emptyList(),
    val repeatable: Boolean = false,
    val descriptionKey: String,
  ) {
    /** The flag's long form including its leading dashes, or `null` if the CLI declares no long form. */
    val longOption: String? get() = long?.let { "--$it" }

    /** The flag's short form including its leading dash, or `null` if the CLI declares no short form. */
    val shortOption: String? get() = short?.let { "-$it" }

    /** The form used to name this flag in messages and completion variants. */
    val option: String get() = longOption ?: checkNotNull(shortOption) { "flag has neither a long nor a short form" }

    /** Returns whether [token] names this flag, in any of its long, short, or attached-value forms. */
    fun matches(token: String): Boolean {
      longOption?.let { if (token == it || token.startsWith("$it=")) return true }

      val shortOption = shortOption ?: return false
      // a value can be attached directly to a short option, as in `-tMyTest`
      return token == shortOption || (value != FlagValue.NONE && token.startsWith(shortOption))
    }

    /** Returns whether [token] is a form of this flag that consumes the *following* token as its value. */
    fun takesNextToken(token: String): Boolean =
      value == FlagValue.REQUIRED && (token == longOption || token == shortOption)
  }

  /**
   * A single Elide command.
   *
   * @param name Name of the command as it appears on the command line.
   * @param aliases Alternative names the CLI accepts for this command.
   * @param flags Flags declared by the command itself; global flags apply on top of these.
   * @param positionals Statically known positional arguments, such as build target groups.
   * @param passthrough Whether program or tool arguments must be separated from Elide's own by `--`.
   * @param descriptionKey Key of the command's description in the plugin's string bundle.
   */
  data class Command(
    val name: String,
    val aliases: List<String> = emptyList(),
    val flags: List<Flag> = emptyList(),
    val positionals: List<String> = emptyList(),
    val passthrough: Boolean = false,
    val descriptionKey: String,
  )

  /** The command named by an argument vector, as resolved by [parse]. */
  data class Invocation(
    /** The invoked command, or `null` when no command token is present, e.g. for `elide app.ts`. */
    val command: Command?,
    /** Index of the command token in the argument vector, or `-1` when [command] is `null`. */
    val commandIndex: Int,
    /** Index of the first standalone `--` in the argument vector, or `-1` when there is none. */
    val passthroughIndex: Int,
  )

  /** Separator after which arguments belong to the program or tool being invoked, rather than to Elide. */
  const val PASSTHROUGH = "--"

  // ---------------------------------------------------------------------------------------------------------------
  // Flags
  // ---------------------------------------------------------------------------------------------------------------

  private val PROJECT_PATH = Flag("project", 'p', FlagValue.REQUIRED, descriptionKey = "cli.flag.project")
  private val DEBUG = Flag("debug", descriptionKey = "cli.flag.debug")
  private val VERBOSE = Flag("verbose", 'v', descriptionKey = "cli.flag.verbose")
  private val QUIET = Flag("quiet", 'q', descriptionKey = "cli.flag.quiet")
  private val TIMEOUT = Flag("timeout", value = FlagValue.REQUIRED, descriptionKey = "cli.flag.timeout")
  private val COLOR = Flag("color", descriptionKey = "cli.flag.color")
  private val NO_COLOR = Flag("no-color", descriptionKey = "cli.flag.no-color")

  private val COVERAGE = Flag(
    long = "coverage",
    value = FlagValue.OPTIONAL_EQUALS,
    values = listOf("auto"),
    descriptionKey = "cli.flag.coverage",
  )

  private val PROFILER = Flag(
    long = "profiler",
    value = FlagValue.OPTIONAL_EQUALS,
    values = listOf("cputracing", "cpusampling", "memtracing"),
    descriptionKey = "cli.flag.profiler",
  )

  private val INSIGHTS = Flag(
    long = "insights",
    value = FlagValue.REQUIRED,
    repeatable = true,
    descriptionKey = "cli.flag.insights",
  )

  private val ERROR_FORMAT = Flag(
    long = "error-format",
    value = FlagValue.REQUIRED,
    values = listOf("auto", "pretty", "plain"),
    descriptionKey = "cli.flag.error-format",
  )

  private val ABORT_ON_UNCAUGHT = Flag("abort-on-uncaught-exception", descriptionKey = "cli.flag.abort")
  private val SANDBOX = Flag("sandbox", descriptionKey = "cli.flag.sandbox")

  private val ALLOW_READ = Flag(
    long = "allow-read",
    value = FlagValue.OPTIONAL_EQUALS,
    repeatable = true,
    descriptionKey = "cli.flag.allow-read",
  )

  private val ALLOW_WRITE = Flag(
    long = "allow-write",
    value = FlagValue.OPTIONAL_EQUALS,
    repeatable = true,
    descriptionKey = "cli.flag.allow-write",
  )

  private val ALLOW_NET = Flag(
    long = "allow-net",
    value = FlagValue.OPTIONAL_EQUALS,
    repeatable = true,
    descriptionKey = "cli.flag.allow-net",
  )

  private val ALLOW_RUN = Flag(
    long = "allow-run",
    value = FlagValue.OPTIONAL_EQUALS,
    repeatable = true,
    descriptionKey = "cli.flag.allow-run",
  )

  private val DENY_NET = Flag(
    long = "deny-net",
    value = FlagValue.REQUIRED_EQUALS,
    repeatable = true,
    descriptionKey = "cli.flag.deny-net",
  )

  private val DENY_RUN = Flag(
    long = "deny-run",
    value = FlagValue.REQUIRED_EQUALS,
    repeatable = true,
    descriptionKey = "cli.flag.deny-run",
  )

  private val FS_AUDIT = Flag("fs-audit", descriptionKey = "cli.flag.fs-audit")
  private val ALLOW_THREADS = Flag("allow-threads", descriptionKey = "cli.flag.allow-threads")
  private val NO_NATIVE = Flag("no-native", descriptionKey = "cli.flag.no-native")

  private val VM = Flag(
    long = "vm",
    short = 'X',
    value = FlagValue.REQUIRED,
    repeatable = true,
    descriptionKey = "cli.flag.vm",
  )

  /**
   * Flag that turns on the CLI's debugging features.
   *
   * For JVM entrypoints, `elide run --debugger` launches the guest JVM with
   * `-agentlib:jdwp=transport=dt_socket,server=y,suspend=y`: the CLI owns the socket and the program stays suspended
   * until a debugger dials in. For guest languages the same flag activates the Chrome DevTools or Debug Adapter
   * protocol instead.
   */
  val DEBUGGER: Flag = Flag(
    long = "debugger",
    value = FlagValue.OPTIONAL_EQUALS,
    values = listOf("auto", "cdp", "dap"),
    descriptionKey = "cli.flag.debugger",
  )

  private val SNIPPET = Flag(
    long = "snippet",
    short = 's',
    value = FlagValue.REQUIRED,
    repeatable = true,
    descriptionKey = "cli.flag.snippet",
  )

  private val LANGUAGE = Flag(
    long = "language",
    short = 'l',
    value = FlagValue.REQUIRED,
    values = listOf("javascript", "typescript", "python", "kotlin", "java"),
    descriptionKey = "cli.flag.language",
  )

  /** Flag for `elide test` that narrows the run to tests whose name matches a regular expression. */
  val TEST_NAME_PATTERN: Flag = Flag(
    long = "test-name-pattern",
    short = 't',
    value = FlagValue.REQUIRED,
    descriptionKey = "cli.flag.test-name-pattern",
  )

  /** Flags the CLI declares as global: they are accepted before and after the command. */
  val GLOBAL_FLAGS: List<Flag> = listOf(
    PROJECT_PATH,
    DEBUG,
    VERBOSE,
    QUIET,
    TIMEOUT,
    COLOR,
    NO_COLOR,
    COVERAGE,
    INSIGHTS,
    ERROR_FORMAT,
    ABORT_ON_UNCAUGHT,
    SANDBOX,
    ALLOW_READ,
    ALLOW_WRITE,
    ALLOW_NET,
    ALLOW_RUN,
    DENY_NET,
    DENY_RUN,
    FS_AUDIT,
    ALLOW_THREADS,
    NO_NATIVE,
    VM,
  )

  /**
   * Flags accepted in root position, where they configure the implicit `run`, and repeated by [RUN] itself.
   *
   * Unlike [GLOBAL_FLAGS] these are rejected after any other command.
   */
  val ROOT_ONLY_FLAGS: List<Flag> = listOf(DEBUGGER, PROFILER, SNIPPET, LANGUAGE)

  // ---------------------------------------------------------------------------------------------------------------
  // Commands
  // ---------------------------------------------------------------------------------------------------------------

  /** `elide run`: runs a file, a named script, or the project's entrypoint. */
  val RUN: Command = Command(
    name = "run",
    flags = listOf(DEBUGGER, PROFILER, COVERAGE, INSIGHTS, SNIPPET, LANGUAGE),
    passthrough = true,
    descriptionKey = "cli.command.run",
  )

  /** `elide serve`: serves a directory or script over HTTP. */
  val SERVE: Command = Command(
    name = "serve",
    aliases = listOf("start"),
    flags = listOf(
      Flag("host", value = FlagValue.REQUIRED, descriptionKey = "cli.flag.host"),
      Flag("port", value = FlagValue.REQUIRED, descriptionKey = "cli.flag.serve.port"),
      Flag("no-tui", descriptionKey = "cli.flag.no-tui"),
    ),
    passthrough = true,
    descriptionKey = "cli.command.serve",
  )

  /** `elide dev`: serves a project with file watching enabled. */
  val DEV: Command = Command(
    name = "dev",
    flags = listOf(
      Flag("host", value = FlagValue.REQUIRED, descriptionKey = "cli.flag.host"),
      Flag("port", value = FlagValue.REQUIRED, descriptionKey = "cli.flag.dev.port"),
      Flag("no-tui", descriptionKey = "cli.flag.no-tui"),
      Flag("no-watch", descriptionKey = "cli.flag.no-watch"),
    ),
    passthrough = true,
    descriptionKey = "cli.command.dev",
  )

  /** `elide install`: resolves and installs the project's declared dependencies. */
  val INSTALL: Command = Command(
    name = "install",
    flags = listOf(
      Flag("slim", descriptionKey = "cli.flag.slim"),
      Flag("fresh", descriptionKey = "cli.flag.fresh"),
      Flag("direct", descriptionKey = "cli.flag.direct"),
      Flag(
        long = "with",
        value = FlagValue.REQUIRED,
        values = listOf("sources", "docs", "javadoc"),
        repeatable = true,
        descriptionKey = "cli.flag.with",
      ),
      Flag(
        long = "ecosystems",
        value = FlagValue.REQUIRED,
        values = listOf("maven", "npm", "pypi"),
        repeatable = true,
        descriptionKey = "cli.flag.ecosystems",
      ),
      Flag("workspace", descriptionKey = "cli.flag.workspace"),
    ),
    descriptionKey = "cli.command.install",
  )

  /** `elide build`: runs build targets declared by the project. */
  val BUILD: Command = Command(
    name = "build",
    flags = listOf(
      Flag("inspect", 'i', descriptionKey = "cli.flag.inspect"),
      Flag("no-cache", descriptionKey = "cli.flag.no-cache"),
      Flag("offline", descriptionKey = "cli.flag.offline"),
    ),
    // the project's own task names are only known after an `--inspect` run; these groups always exist
    positionals = listOf("deps", "compile", "test", "clean"),
    descriptionKey = "cli.command.build",
  )

  /** `elide test`: discovers and runs the project's tests. */
  val TEST: Command = Command(
    name = "test",
    flags = listOf(
      TEST_NAME_PATTERN,
      Flag("bail", value = FlagValue.OPTIONAL_EQUALS, descriptionKey = "cli.flag.bail"),
      Flag("test-timeout", value = FlagValue.REQUIRED, descriptionKey = "cli.flag.test-timeout"),
      Flag("only", descriptionKey = "cli.flag.only"),
      Flag("concurrency", value = FlagValue.REQUIRED, descriptionKey = "cli.flag.concurrency"),
      Flag(
        long = "reporter",
        value = FlagValue.REQUIRED,
        values = listOf("console", "junit"),
        descriptionKey = "cli.flag.reporter",
      ),
      Flag("reporter-outfile", value = FlagValue.REQUIRED, descriptionKey = "cli.flag.reporter-outfile"),
    ),
    descriptionKey = "cli.command.test",
  )

  /** `elide format`: runs the formatters configured for the project. */
  val FORMAT: Command = Command(
    name = "format",
    aliases = listOf("fmt"),
    flags = listOf(
      Flag("list-files", descriptionKey = "cli.flag.list-files"),
      Flag("list-diffs", value = FlagValue.OPTIONAL_EQUALS, descriptionKey = "cli.flag.list-diffs"),
    ),
    passthrough = true,
    descriptionKey = "cli.command.format",
  )

  /** `elide project`: interrogates the current project. */
  val PROJECT: Command = Command(
    name = "project",
    positionals = listOf("info", "advice"),
    descriptionKey = "cli.command.project",
  )

  /** `elide python`: runs Python programs with a CPython-compatible argument vector. */
  val PYTHON: Command = Command(
    name = "python",
    flags = listOf(
      Flag(short = 'c', value = FlagValue.REQUIRED, descriptionKey = "cli.flag.python.c"),
      Flag(short = 's', descriptionKey = "cli.flag.python.s"),
    ),
    descriptionKey = "cli.command.python",
  )

  /** `elide classpath`: prints resolved classpaths for the project's source sets. */
  val CLASSPATH: Command = Command(
    name = "classpath",
    flags = listOf(Flag("offline", descriptionKey = "cli.flag.offline")),
    descriptionKey = "cli.command.classpath",
  )

  /** `elide manifest`: prints the parsed project manifest. */
  val MANIFEST: Command = Command(name = "manifest", descriptionKey = "cli.command.manifest")

  /** `elide info`: prints information about the Elide binary in use. */
  val INFO: Command = Command(
    name = "info",
    flags = listOf(Flag("cargo-lockfile", descriptionKey = "cli.flag.cargo-lockfile")),
    descriptionKey = "cli.command.info",
  )

  /** Commands that forward everything after `--` to an embedded JVM tool. */
  val TOOLS: List<Command> = listOf(
    "java",
    "javac",
    "kotlinc",
    "jar",
    "javadoc",
    "javap",
    "jdeps",
    "ktfmt",
    "javaformat",
    "mvn",
    "native-image",
  ).map { Command(name = it, passthrough = true, descriptionKey = "cli.command.$it") }

  /** Every modelled command, in the order they are offered as completion variants. */
  val COMMANDS: List<Command> = listOf(
    RUN,
    SERVE,
    DEV,
    INSTALL,
    BUILD,
    TEST,
    FORMAT,
    PROJECT,
    PYTHON,
    CLASSPATH,
    MANIFEST,
    INFO,
  ) + TOOLS

  private val COMMANDS_BY_NAME: Map<String, Command> = buildMap {
    for (command in COMMANDS) {
      put(command.name, command)
      for (alias in command.aliases) put(alias, command)
    }
  }

  /** Returns the command named by [token], resolving aliases, or `null` if it names no known command. */
  fun command(token: String): Command? = COMMANDS_BY_NAME[token]

  /**
   * Resolves which command [args] invokes.
   *
   * The CLI's root parser accepts global flags before the command, so leading flags are skipped, including the value
   * of any flag that takes one as a separate token. A leading non-flag token that names no command is a file to run,
   * which yields a `null` [Invocation.command].
   */
  fun parse(args: List<String>): Invocation {
    val rootFlags = ROOT_ONLY_FLAGS + GLOBAL_FLAGS
    val passthroughIndex = args.indexOf(PASSTHROUGH)

    var index = 0
    while (index < args.size) {
      val token = args[index]
      if (token == PASSTHROUGH) break

      if (token.startsWith('-')) {
        // a flag such as `--project ./app` hides its value in the next token, which is not a command
        if (rootFlags.any { it.takesNextToken(token) }) index++
        index++
        continue
      }

      val command = command(token) ?: break
      return Invocation(command, index, passthroughIndex)
    }

    return Invocation(null, -1, passthroughIndex)
  }
}
