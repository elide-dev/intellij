@file:Suppress("RedundantVisibilityModifier", "Unused", "EnumEntryName")

package dev.elide.tooling.manifest.opentelemetry

import kotlin.Boolean
import kotlin.Double
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.emptyList
import kotlin.collections.emptyMap
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

/**
 * Service identity, exported as `service.*` resource attributes.
 */
@Serializable
@SerialName("elide.opentelemetry.ServiceSettings")
public data class ServiceSettings(
  /**
   * `service.name`.
   */
  public val name: String? = null,
  /**
   * `service.version`.
   */
  public val version: String? = null,
  /**
   * `service.namespace`.
   */
  public val namespace: String? = null,
  /**
   * `deployment.environment.name`.
   */
  public val environment: String? = null,
)

/**
 * Settings for one signal of a destination; set properties replace the destination's own.
 */
@Serializable
@SerialName("elide.opentelemetry.SignalOverride")
public data class SignalOverride(
  /**
   * Complete URL for this signal, used as given.
   */
  public val endpoint: String? = null,
  /**
   * Wire protocol for this signal.
   */
  public val protocol: Protocol? = null,
  /**
   * Literal, non-secret headers.
   */
  public val headers: Map<String, String> = emptyMap(),
  /**
   * Headers whose values are read from the named environment variables.
   */
  public val headerEnv: Map<String, String> = emptyMap(),
  /**
   * Body compression.
   */
  public val compression: Compression? = null,
  /**
   * Per-request timeout.
   */
  public val timeout: Duration? = null,
)

/**
 * A place telemetry is sent.
 */
@Serializable
@SerialName("elide.opentelemetry.Destination")
public data class Destination(
  /**
   * Provider preset; `otlp` when unset.
   */
  public val provider: Provider? = null,
  /**
   * Base endpoint; for presets, replaces the provider's host. HTTP signal paths are appended.
   */
  public val endpoint: String? = null,
  /**
   * Wire protocol; `http/protobuf` when unset.
   */
  public val protocol: Protocol? = null,
  /**
   * Literal, non-secret headers.
   */
  public val headers: Map<String, String> = emptyMap(),
  /**
   * Headers whose values are read from the named environment variables.
   */
  public val headerEnv: Map<String, String> = emptyMap(),
  /**
   * Environment variable holding the provider's API key or token. Defaults: `DD_API_KEY`, `POSTHOG_PROJECT_TOKEN`,
   * `HONEYCOMB_API_KEY`, `GRAFANA_CLOUD_OTLP_CREDENTIAL` (`<instance id>:<token>`).
   */
  public val credential: String? = null,
  /**
   * Environment variable holding a Sentry DSN; `SENTRY_DSN` when unset.
   */
  public val dsn: String? = null,
  /**
   * Datadog site, such as `us5`, `eu`, or `datadoghq.com`; `DD_SITE` or `datadoghq.com` when unset.
   */
  public val site: String? = null,
  /**
   * PostHog or Honeycomb region (`us`, `eu`), or Grafana Cloud zone (`prod-us-east-0`).
   */
  public val region: String? = null,
  /**
   * Signals to send here; every signal the provider accepts when unset.
   */
  public val signals: List<Signal>? = null,
  /**
   * Body compression; `gzip` when unset.
   */
  public val compression: Compression? = null,
  /**
   * Per-request timeout; 10 seconds when unset.
   */
  public val timeout: Duration? = null,
  /**
   * Per-signal overrides.
   */
  public val perSignal: Map<Signal, SignalOverride> = emptyMap(),
)

/**
 * Application trace settings.
 */
@Serializable
@SerialName("elide.opentelemetry.TraceSettings")
public data class TraceSettings(
  /**
   * Record application spans; on when unset.
   */
  public val enabled: Boolean? = null,
  /**
   * Head sampler; `parentbased_always_on` when unset.
   */
  public val sampler: Sampler? = null,
  /**
   * Ratio for ratio-based samplers.
   */
  public val ratio: Double? = null,
  /**
   * Propagators for incoming and outgoing requests; `tracecontext` and `baggage` when unset.
   */
  public val propagators: List<Propagator>? = null,
)

/**
 * Application metric settings.
 */
@Serializable
@SerialName("elide.opentelemetry.MetricSettings")
public data class MetricSettings(
  /**
   * Record application metrics; on when unset.
   */
  public val enabled: Boolean? = null,
  /**
   * Export interval; 60 seconds when unset.
   */
  public val interval: Duration? = null,
  /**
   * Aggregation temporality; `cumulative` when unset, `delta` when sending metrics to Datadog.
   */
  public val temporality: Temporality? = null,
)

/**
 * Application log settings.
 */
@Serializable
@SerialName("elide.opentelemetry.LogSettings")
public data class LogSettings(
  /**
   * Export application log records; on when unset.
   */
  public val enabled: Boolean? = null,
  /**
   * Minimum severity; `info` when unset.
   */
  public val level: Level? = null,
  /**
   * Export `console.*`, Python `logging`, and similar output as log records; on when unset.
   */
  public val console: Boolean? = null,
)

/**
 * Guest code profiling settings (experimental).
 */
@Serializable
@SerialName("elide.opentelemetry.ProfileSettings")
public data class ProfileSettings(
  /**
   * Sample guest CPU usage; off when unset.
   */
  public val enabled: Boolean? = null,
  /**
   * Samples per second; 100 when unset.
   */
  public val frequency: Long? = null,
)

/**
 * Elide's own telemetry: serving, fetch, and lifecycle spans, runtime metrics, and runtime logs.
 */
@Serializable
@SerialName("elide.opentelemetry.RuntimeSettings")
public data class RuntimeSettings(
  /**
   * Runtime spans; on when unset.
   */
  public val traces: Boolean? = null,
  /**
   * Runtime metrics; on when unset.
   */
  public val metrics: Boolean? = null,
  /**
   * Minimum severity of Elide's own log records to export; `warn` when unset.
   */
  public val logs: Level? = null,
)

/**
 * Runtime HTTP baggage propagation. Inbound baggage is untrusted input, so each direction is off
 * until enabled, and `OTEL_PROPAGATORS` does not enable it for runtime HTTP.
 */
@Serializable
@SerialName("elide.opentelemetry.BaggageSettings")
public data class BaggageSettings(
  /**
   * Parse inbound `baggage` headers on served requests into the request context.
   */
  public val inbound: Boolean = false,
  /**
   * Inject the current context's baggage into runtime HTTP client requests; a caller-set header
   * is never overwritten.
   */
  public val outbound: Boolean = false,
  /**
   * Keys allowed in both directions; all keys pass an enabled direction when unset.
   */
  public val allow: List<String>? = null,
  /**
   * Inbound keys copied onto the server span as `baggage.<key>` attributes.
   */
  public val spanAttributes: List<String> = emptyList(),
)

/**
 * Runtime context propagation.
 */
@Serializable
@SerialName("elide.opentelemetry.PropagationSettings")
public data class PropagationSettings(
  /**
   * Runtime HTTP baggage.
   */
  public val baggage: BaggageSettings = BaggageSettings(),
)

/**
 * OpenTelemetry settings for this project.
 */
@Serializable
@SerialName("elide.opentelemetry.OpenTelemetrySettings")
public data class OpenTelemetrySettings(
  /**
   * `false` turns telemetry off, whatever the environment says.
   */
  public val enabled: Boolean? = null,
  /**
   * Service identity.
   */
  public val service: ServiceSettings = ServiceSettings(),
  /**
   * Extra resource attributes.
   */
  public val resource: Map<String, ResourceValue> = emptyMap(),
  /**
   * Named destinations. Telemetry is off when there are none.
   */
  public val destinations: Map<String, Destination> = emptyMap(),
  /**
   * Application traces.
   */
  public val traces: TraceSettings = TraceSettings(),
  /**
   * Application metrics.
   */
  public val metrics: MetricSettings = MetricSettings(),
  /**
   * Application logs.
   */
  public val logs: LogSettings = LogSettings(),
  /**
   * Guest profiling.
   */
  public val profiles: ProfileSettings = ProfileSettings(),
  /**
   * Elide's own telemetry.
   */
  public val runtime: RuntimeSettings = RuntimeSettings(),
  /**
   * Runtime context propagation.
   */
  public val propagation: PropagationSettings = PropagationSettings(),
  /**
   * Final export deadline at exit; 5 seconds when unset.
   */
  public val shutdownTimeout: Duration? = null,
)

/**
 * Telemetry provider preset. `otlp` is any OTLP endpoint, such as a collector or agent.
 */
@Serializable
@SerialName("elide.opentelemetry.Provider")
public enum class Provider {
  @SerialName("otlp")
  Otlp,
  @SerialName("sentry")
  Sentry,
  @SerialName("datadog")
  Datadog,
  @SerialName("posthog")
  Posthog,
  @SerialName("honeycomb")
  Honeycomb,
  @SerialName("grafana")
  Grafana,
}

/**
 * OTLP wire protocol.
 */
@Serializable
@SerialName("elide.opentelemetry.Protocol")
public enum class Protocol {
  @SerialName("grpc")
  Grpc,
  @SerialName("http/protobuf")
  Http_protobuf,
  @SerialName("http/json")
  Http_json,
}

/**
 * Telemetry signal. Errors travel as trace exception events and error-severity log records.
 */
@Serializable
@SerialName("elide.opentelemetry.Signal")
public enum class Signal {
  @SerialName("traces")
  Traces,
  @SerialName("metrics")
  Metrics,
  @SerialName("logs")
  Logs,
  @SerialName("profiles")
  Profiles,
}

/**
 * Request body compression.
 */
@Serializable
@SerialName("elide.opentelemetry.Compression")
public enum class Compression {
  @SerialName("gzip")
  Gzip,
  @SerialName("none")
  None,
}

/**
 * Head sampler, as named by `OTEL_TRACES_SAMPLER`.
 */
@Serializable
@SerialName("elide.opentelemetry.Sampler")
public enum class Sampler {
  @SerialName("always_on")
  Always_on,
  @SerialName("always_off")
  Always_off,
  @SerialName("traceidratio")
  Traceidratio,
  @SerialName("parentbased_always_on")
  Parentbased_always_on,
  @SerialName("parentbased_always_off")
  Parentbased_always_off,
  @SerialName("parentbased_traceidratio")
  Parentbased_traceidratio,
}

/**
 * Context propagator.
 */
@Serializable
@SerialName("elide.opentelemetry.Propagator")
public enum class Propagator {
  @SerialName("tracecontext")
  Tracecontext,
  @SerialName("baggage")
  Baggage,
  @SerialName("none")
  None,
}

/**
 * Log severity threshold.
 */
@Serializable
@SerialName("elide.opentelemetry.Level")
public enum class Level {
  @SerialName("trace")
  Trace,
  @SerialName("debug")
  Debug,
  @SerialName("info")
  Info,
  @SerialName("warn")
  Warn,
  @SerialName("error")
  Error,
  @SerialName("fatal")
  Fatal,
  @SerialName("off")
  Off,
}

/**
 * Metric aggregation temporality.
 */
@Serializable
@SerialName("elide.opentelemetry.Temporality")
public enum class Temporality {
  @SerialName("cumulative")
  Cumulative,
  @SerialName("delta")
  Delta,
}

/**
 * Name of an environment variable; holds a credential so it never appears in the manifest.
 */
public typealias EnvVarName = String

/**
 * HTTP(S) URL.
 */
public typealias EndpointUrl = String

/**
 * Resource attribute value.
 */
@Serializable
@SerialName("elide.opentelemetry.ResourceValue")
public sealed interface ResourceValue {
  @Serializable(with = OfString.Companion::class)
  @JvmInline
  public value class OfString(
    public val `value`: String,
  ) : ResourceValue {
    public companion object : KSerializer<OfString> {
      override val descriptor: SerialDescriptor =
          buildClassSerialDescriptor("elide.opentelemetry.ResourceValue.OfString") {
        element<String>("value",isOptional=false)
      }

      override fun serialize(encoder: Encoder, `value`: OfString) {
        encoder.encodeStructure(descriptor) {
          encodeStringElement(descriptor,0,value.value)
        }
      }

      override fun deserialize(decoder: Decoder): OfString = decoder.decodeStructure(descriptor) {
        var value: String? = null
        while (true) {
          when (val index = decodeElementIndex(descriptor)) {
            0 -> value = decodeStringElement(descriptor,0)
            CompositeDecoder.DECODE_DONE -> break
            else -> error("""Unexpected index: $index""")
          }
        }
        OfString(value!!)
      }
    }
  }

  @Serializable(with = OfBoolean.Companion::class)
  @JvmInline
  public value class OfBoolean(
    public val `value`: Boolean,
  ) : ResourceValue {
    public companion object : KSerializer<OfBoolean> {
      override val descriptor: SerialDescriptor =
          buildClassSerialDescriptor("elide.opentelemetry.ResourceValue.OfBoolean") {
        element<Boolean>("value",isOptional=false)
      }

      override fun serialize(encoder: Encoder, `value`: OfBoolean) {
        encoder.encodeStructure(descriptor) {
          encodeBooleanElement(descriptor,0,value.value)
        }
      }

      override fun deserialize(decoder: Decoder): OfBoolean = decoder.decodeStructure(descriptor) {
        var value: Boolean? = null
        while (true) {
          when (val index = decodeElementIndex(descriptor)) {
            0 -> value = decodeBooleanElement(descriptor,0)
            CompositeDecoder.DECODE_DONE -> break
            else -> error("""Unexpected index: $index""")
          }
        }
        OfBoolean(value!!)
      }
    }
  }

  @Serializable(with = OfInt.Companion::class)
  @JvmInline
  public value class OfInt(
    public val `value`: Long,
  ) : ResourceValue {
    public companion object : KSerializer<OfInt> {
      override val descriptor: SerialDescriptor =
          buildClassSerialDescriptor("elide.opentelemetry.ResourceValue.OfInt") {
        element<Long>("value",isOptional=false)
      }

      override fun serialize(encoder: Encoder, `value`: OfInt) {
        encoder.encodeStructure(descriptor) {
          encodeLongElement(descriptor,0,value.value)
        }
      }

      override fun deserialize(decoder: Decoder): OfInt = decoder.decodeStructure(descriptor) {
        var value: Long? = null
        while (true) {
          when (val index = decodeElementIndex(descriptor)) {
            0 -> value = decodeLongElement(descriptor,0)
            CompositeDecoder.DECODE_DONE -> break
            else -> error("""Unexpected index: $index""")
          }
        }
        OfInt(value!!)
      }
    }
  }

  @Serializable(with = OfFloat.Companion::class)
  @JvmInline
  public value class OfFloat(
    public val `value`: Double,
  ) : ResourceValue {
    public companion object : KSerializer<OfFloat> {
      override val descriptor: SerialDescriptor =
          buildClassSerialDescriptor("elide.opentelemetry.ResourceValue.OfFloat") {
        element<Double>("value",isOptional=false)
      }

      override fun serialize(encoder: Encoder, `value`: OfFloat) {
        encoder.encodeStructure(descriptor) {
          encodeDoubleElement(descriptor,0,value.value)
        }
      }

      override fun deserialize(decoder: Decoder): OfFloat = decoder.decodeStructure(descriptor) {
        var value: Double? = null
        while (true) {
          when (val index = decodeElementIndex(descriptor)) {
            0 -> value = decodeDoubleElement(descriptor,0)
            CompositeDecoder.DECODE_DONE -> break
            else -> error("""Unexpected index: $index""")
          }
        }
        OfFloat(value!!)
      }
    }
  }
}

/**
 * W3C Baggage key: an HTTP token.
 */
public typealias BaggageKey = String
