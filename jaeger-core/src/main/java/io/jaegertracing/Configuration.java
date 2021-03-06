/*
 * Copyright (c) 2016, Uber Technologies, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.jaegertracing;

import io.jaegertracing.metrics.Metrics;
import io.jaegertracing.metrics.MetricsFactory;
import io.jaegertracing.metrics.NoopMetricsFactory;
import io.jaegertracing.propagation.B3TextMapCodec;
import io.jaegertracing.propagation.Codec;
import io.jaegertracing.propagation.CompositeCodec;
import io.jaegertracing.propagation.TextMapCodec;
import io.jaegertracing.reporters.CompositeReporter;
import io.jaegertracing.reporters.LoggingReporter;
import io.jaegertracing.reporters.RemoteReporter;
import io.jaegertracing.reporters.Reporter;
import io.jaegertracing.samplers.ConstSampler;
import io.jaegertracing.samplers.HttpSamplingManager;
import io.jaegertracing.samplers.ProbabilisticSampler;
import io.jaegertracing.samplers.RateLimitingSampler;
import io.jaegertracing.samplers.RemoteControlledSampler;
import io.jaegertracing.samplers.Sampler;
import io.jaegertracing.senders.Sender;
import io.jaegertracing.senders.SenderFactory;
import io.jaegertracing.senders.SenderResolver;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * This class is designed to provide {@link Tracer} or {@link Tracer.Builder} when Jaeger client
 * configuration is provided in environmental or property variables. It also simplifies creation
 * of the client from configuration files.
 */
@Slf4j
public class Configuration {
  /**
   * Prefix for all properties used to configure the Jaeger tracer.
   */
  public static final String JAEGER_PREFIX = "JAEGER_";

  /**
   * The full URL to the {@code traces} endpoint, like https://jaeger-collector:14268/api/traces
   */
  public static final String JAEGER_ENDPOINT = JAEGER_PREFIX + "ENDPOINT";

  /**
   * The Auth Token to be added as "Bearer" on Authorization headers for requests sent to the endpoint
   */
  public static final String JAEGER_AUTH_TOKEN = JAEGER_PREFIX + "AUTH_TOKEN";

  /**
   * The Basic Auth username to be added on Authorization headers for requests sent to the endpoint
   */
  public static final String JAEGER_USER = JAEGER_PREFIX + "USER";

  /**
   * The Basic Auth password to be added on Authorization headers for requests sent to the endpoint
   */
  public static final String JAEGER_PASSWORD = JAEGER_PREFIX + "PASSWORD";

  /**
   * The host name used to locate the agent.
   */
  public static final String JAEGER_AGENT_HOST = JAEGER_PREFIX + "AGENT_HOST";

  /**
   * The port used to locate the agent.
   */
  public static final String JAEGER_AGENT_PORT = JAEGER_PREFIX + "AGENT_PORT";

  /**
   * Whether the reporter should log the spans.
   */
  public static final String JAEGER_REPORTER_LOG_SPANS = JAEGER_PREFIX + "REPORTER_LOG_SPANS";

  /**
   * The maximum queue size for use when reporting spans remotely.
   */
  public static final String JAEGER_REPORTER_MAX_QUEUE_SIZE = JAEGER_PREFIX + "REPORTER_MAX_QUEUE_SIZE";

  /**
   * The flush interval when reporting spans remotely.
   */
  public static final String JAEGER_REPORTER_FLUSH_INTERVAL = JAEGER_PREFIX + "REPORTER_FLUSH_INTERVAL";

  /**
   * The sampler type.
   */
  public static final String JAEGER_SAMPLER_TYPE = JAEGER_PREFIX + "SAMPLER_TYPE";

  /**
   * The sampler parameter (number).
   */
  public static final String JAEGER_SAMPLER_PARAM = "JAEGER_SAMPLER_PARAM";

  /**
   * The sampler manager host:port.
   */
  public static final String JAEGER_SAMPLER_MANAGER_HOST_PORT = JAEGER_PREFIX + "SAMPLER_MANAGER_HOST_PORT";

  /**
   * The service name.
   */
  public static final String JAEGER_SERVICE_NAME = JAEGER_PREFIX + "SERVICE_NAME";

  /**
   * The tracer level tags.
   */
  public static final String JAEGER_TAGS = JAEGER_PREFIX + "TAGS";

  /**
   * Comma separated list of formats to use for propagating the trace context. Default will the
   * standard Jaeger format. Valid values are jaeger and b3.
   */
  public static final String JAEGER_PROPAGATION = JAEGER_PREFIX + "PROPAGATION";

  /**
   * When there are multiple service providers for the {@link SenderFactory} available,
   * this var is used to select a {@link SenderFactory} by matching it with
   * {@link SenderFactory#getType()}.
   *
   */
  public static final String JAEGER_SENDER_FACTORY = JAEGER_PREFIX + "SENDER_FACTORY";

  /**
   * The supported trace context propagation formats.
   */
  public enum Propagation {

    /**
     * The default Jaeger trace context propagation format.
     */
    JAEGER,

    /**
     * The Zipkin B3 trace context propagation format.
     */
    B3
  }

  /**
   * The serviceName that the tracer will use
   */
  private String serviceName;
  private SamplerConfiguration samplerConfig;
  private ReporterConfiguration reporterConfig;
  private CodecConfiguration codecConfig;
  private MetricsFactory metricsFactory;
  private Map<String, String> tracerTags;

  /**
   * lazy singleton Tracer initialized in getTracer() method.
   */
  private Tracer tracer;

  public Configuration(String serviceName) {
    this.serviceName = Tracer.Builder.checkValidServiceName(serviceName);
  }


  /**
   * @return Configuration object from environmental variables
   */
  public static Configuration fromEnv() {
    return new Configuration(getProperty(JAEGER_SERVICE_NAME))
        .withTracerTags(tracerTagsFromEnv())
        .withReporter(ReporterConfiguration.fromEnv())
        .withSampler(SamplerConfiguration.fromEnv())
        .withCodec(CodecConfiguration.fromEnv());
  }

  public Tracer.Builder getTracerBuilder() {
    if (reporterConfig == null) {
      reporterConfig = new ReporterConfiguration();
    }
    if (samplerConfig == null) {
      samplerConfig = new SamplerConfiguration();
    }
    if (codecConfig == null) {
      codecConfig = new CodecConfiguration();
    }
    if (metricsFactory == null) {
      metricsFactory = loadMetricsFactory();
    }
    Metrics metrics = new Metrics(metricsFactory);
    Reporter reporter = reporterConfig.getReporter(metrics);
    Sampler sampler = samplerConfig.createSampler(serviceName, metrics);
    Tracer.Builder builder = new Tracer.Builder(serviceName)
        .withSampler(sampler)
        .withReporter(reporter)
        .withMetrics(metrics)
        .withTags(tracerTags);
    codecConfig.apply(builder);
    return builder;
  }

  public synchronized io.opentracing.Tracer getTracer() {
    if (tracer != null) {
      return tracer;
    }

    tracer = getTracerBuilder().build();
    log.info("Initialized tracer={}", tracer);

    return tracer;
  }

  public synchronized void closeTracer() {
    if (tracer != null) {
      tracer.close();
    }
  }

  private MetricsFactory loadMetricsFactory() {
    ServiceLoader<MetricsFactory> loader = ServiceLoader.load(MetricsFactory.class);

    Iterator<MetricsFactory> iterator = loader.iterator();
    if (iterator.hasNext()) {
      MetricsFactory metricsFactory = iterator.next();
      log.info("Found a Metrics Factory service: {}", metricsFactory.getClass());
      return metricsFactory;
    }

    return new NoopMetricsFactory();
  }

  /**
   * @param metricsFactory the MetricsFactory to use on the Tracer to be built
   */
  public Configuration withMetricsFactory(MetricsFactory metricsFactory) {
    this.metricsFactory = metricsFactory;
    return this;
  }

  public Configuration withServiceName(String serviceName) {
    this.serviceName = Tracer.Builder.checkValidServiceName(serviceName);
    return this;
  }

  public Configuration withReporter(ReporterConfiguration reporterConfig) {
    this.reporterConfig = reporterConfig;
    return this;
  }

  public Configuration withSampler(SamplerConfiguration samplerConfig) {
    this.samplerConfig = samplerConfig;
    return this;
  }

  public Configuration withCodec(CodecConfiguration codecConfig) {
    this.codecConfig = codecConfig;
    return this;
  }

  public Configuration withTracerTags(Map<String, String> tracerTags) {
    if (tracerTags != null) {
      this.tracerTags = new HashMap<String, String>(tracerTags);
    }
    return this;
  }

  public String getServiceName() {
    return serviceName;
  }

  public ReporterConfiguration getReporter() {
    return reporterConfig;
  }

  public SamplerConfiguration getSampler() {
    return samplerConfig;
  }

  public CodecConfiguration getCodec() {
    return codecConfig;
  }

  public MetricsFactory getMetricsFactory() {
    return metricsFactory;
  }

  public Map<String, String> getTracerTags() {
    return tracerTags == null ? null : Collections.unmodifiableMap(tracerTags);
  }

  /**
   * SamplerConfiguration allows to configure which sampler the tracer will use.
   */
  public static class SamplerConfiguration {
    /**
     * The type of sampler to use in the tracer. Optional. Valid values: remote (default),
     * ratelimiting, probabilistic, const.
     */
    private String type;

    /**
     * The integer or floating point value that makes sense for the correct samplerType. Optional.
     */
    private Number param;

    /**
     * HTTP host:port of the sampling manager that can provide sampling strategy to this service.
     * Optional.
     */
    private String managerHostPort;

    public SamplerConfiguration() {
    }

    public static SamplerConfiguration fromEnv() {
      return new SamplerConfiguration()
          .withType(getProperty(JAEGER_SAMPLER_TYPE))
          .withParam(getPropertyAsNum(JAEGER_SAMPLER_PARAM))
          .withManagerHostPort(getProperty(JAEGER_SAMPLER_MANAGER_HOST_PORT));
    }

    // for tests
    Sampler createSampler(String serviceName, Metrics metrics) {
      String samplerType = stringOrDefault(this.getType(), RemoteControlledSampler.TYPE);
      Number samplerParam = numberOrDefault(this.getParam(), ProbabilisticSampler.DEFAULT_SAMPLING_PROBABILITY);
      String hostPort = stringOrDefault(this.getManagerHostPort(), HttpSamplingManager.DEFAULT_HOST_PORT);

      if (samplerType.equals(ConstSampler.TYPE)) {
        return new ConstSampler(samplerParam.intValue() != 0);
      }

      if (samplerType.equals(ProbabilisticSampler.TYPE)) {
        return new ProbabilisticSampler(samplerParam.doubleValue());
      }

      if (samplerType.equals(RateLimitingSampler.TYPE)) {
        return new RateLimitingSampler(samplerParam.intValue());
      }

      if (samplerType.equals(RemoteControlledSampler.TYPE)) {
        return new RemoteControlledSampler.Builder(serviceName)
            .withSamplingManager(new HttpSamplingManager(hostPort))
            .withInitialSampler(new ProbabilisticSampler(samplerParam.doubleValue()))
            .withMetrics(metrics)
            .build();
      }

      throw new IllegalStateException(String.format("Invalid sampling strategy %s", samplerType));
    }

    public String getType() {
      return type;
    }

    public Number getParam() {
      return param;
    }

    public String getManagerHostPort() {
      return managerHostPort;
    }

    public SamplerConfiguration withType(String type) {
      this.type = type;
      return this;
    }

    public SamplerConfiguration withParam(Number param) {
      this.param = param;
      return this;
    }

    public SamplerConfiguration withManagerHostPort(String managerHostPort) {
      this.managerHostPort = managerHostPort;
      return this;
    }
  }

  /**
   * CodecConfiguration can be used to support additional trace context propagation codec.
   */
  public static class CodecConfiguration {
    private final Map<Format<?>, List<Codec<TextMap>>> codecs;

    public CodecConfiguration() {
      codecs = new HashMap<Format<?>, List<Codec<TextMap>>>();
    }


    private CodecConfiguration(Map<Format<?>, List<Codec<TextMap>>> codecs) {
      this.codecs = codecs;
    }

    public static CodecConfiguration fromEnv() {
      Map<Format<?>, List<Codec<TextMap>>> codecs = new HashMap<Format<?>, List<Codec<TextMap>>>();
      String propagation = getProperty(JAEGER_PROPAGATION);
      if (propagation != null) {
        for (String format : Arrays.asList(propagation.split(","))) {
          try {
            switch (Configuration.Propagation.valueOf(format.toUpperCase())) {
              case JAEGER:
                addCodec(codecs, Format.Builtin.HTTP_HEADERS, new TextMapCodec(true));
                addCodec(codecs, Format.Builtin.TEXT_MAP, new TextMapCodec(false));
                break;
              case B3:
                addCodec(codecs, Format.Builtin.HTTP_HEADERS, new B3TextMapCodec());
                addCodec(codecs, Format.Builtin.TEXT_MAP, new B3TextMapCodec());
                break;
              default:
                log.error("Unhandled propagation format '" + format + "'");
                break;
            }
          } catch (IllegalArgumentException iae) {
            log.error("Unknown propagation format '" + format + "'");
          }
        }
      }
      return new CodecConfiguration(codecs);
    }

    public CodecConfiguration withCodec(Format<?> format, Codec<TextMap> codec) {
      addCodec(codecs, format, codec);
      return this;
    }

    public Map<Format<?>, List<Codec<TextMap>>> getCodecs() {
      return Collections.unmodifiableMap(codecs);
    }

    private static void addCodec(Map<Format<?>, List<Codec<TextMap>>> codecs, Format<?> format, Codec<TextMap> codec) {
      List<Codec<TextMap>> codecList = codecs.get(format);
      if (codecList == null) {
        codecList = new LinkedList<Codec<TextMap>>();
        codecs.put(format, codecList);
      }
      codecList.add(codec);
    }

    public void apply(Tracer.Builder builder) {
      // Replace existing TEXT_MAP and HTTP_HEADERS codec with one that represents the
      // configured propagation formats
      registerCodec(builder, Format.Builtin.HTTP_HEADERS);
      registerCodec(builder, Format.Builtin.TEXT_MAP);
    }

    protected void registerCodec(Tracer.Builder builder, Format<TextMap> format) {
      if (codecs.containsKey(format)) {
        List<Codec<TextMap>> codecsForFormat = codecs.get(format);
        Codec<TextMap> codec = codecsForFormat.size() == 1
            ? codecsForFormat.get(0) : new CompositeCodec<TextMap>(codecsForFormat);
        builder.registerInjector(format, codec);
        builder.registerExtractor(format, codec);
      }
    }
  }

  public static class ReporterConfiguration {
    private Boolean logSpans;
    private Integer flushIntervalMs;
    private Integer maxQueueSize;
    private SenderConfiguration senderConfiguration = new SenderConfiguration();

    public ReporterConfiguration() {
    }

    public static ReporterConfiguration fromEnv() {
      return new ReporterConfiguration()
          .withLogSpans(getPropertyAsBool(JAEGER_REPORTER_LOG_SPANS))
          .withFlushInterval(getPropertyAsInt(JAEGER_REPORTER_FLUSH_INTERVAL))
          .withMaxQueueSize(getPropertyAsInt(JAEGER_REPORTER_MAX_QUEUE_SIZE))
          .withSender(SenderConfiguration.fromEnv());
    }

    public ReporterConfiguration withLogSpans(Boolean logSpans) {
      this.logSpans = logSpans;
      return this;
    }

    public ReporterConfiguration withFlushInterval(Integer flushIntervalMs) {
      this.flushIntervalMs = flushIntervalMs;
      return this;
    }

    public ReporterConfiguration withMaxQueueSize(Integer maxQueueSize) {
      this.maxQueueSize = maxQueueSize;
      return this;
    }

    public ReporterConfiguration withSender(SenderConfiguration senderConfiguration) {
      this.senderConfiguration = senderConfiguration;
      return this;
    }

    private Reporter getReporter(Metrics metrics) {
      Reporter reporter = new RemoteReporter.Builder()
          .withMetrics(metrics)
          .withSender(senderConfiguration.getSender())
          .withFlushInterval(numberOrDefault(this.flushIntervalMs, RemoteReporter.DEFAULT_FLUSH_INTERVAL_MS).intValue())
          .withMaxQueueSize(numberOrDefault(this.maxQueueSize, RemoteReporter.DEFAULT_MAX_QUEUE_SIZE).intValue())
          .build();

      if (Boolean.TRUE.equals(this.logSpans)) {
        Reporter loggingReporter = new LoggingReporter();
        reporter = new CompositeReporter(reporter, loggingReporter);
      }
      return reporter;
    }

    public Boolean getLogSpans() {
      return logSpans;
    }

    public Integer getFlushIntervalMs() {
      return flushIntervalMs;
    }

    public Integer getMaxQueueSize() {
      return maxQueueSize;
    }

    public SenderConfiguration getSenderConfiguration() {
      return senderConfiguration;
    }
  }

  /**
   * Holds the configuration related to the sender. A sender is resolved using a {@link SenderResolver}.
   *
   */
  @Getter
  public static class SenderConfiguration {
    /**
     * A custom sender set by our consumers. If set, nothing else has effect. Optional.
     */
    private Sender sender;

    /**
     * The Agent Host. Has no effect if the sender is set. Optional.
     */
    private String agentHost;

    /**
     * The Agent Port. Has no effect if the sender is set. Optional.
     */
    private Integer agentPort;

    /**
     * The endpoint, like https://jaeger-collector:14268/api/traces
     */
    private String endpoint;

    /**
     * The Auth Token to be added as "Bearer" on Authorization headers for requests sent to the endpoint
     */
    private String authToken;

    /**
     * The Basic Auth username to be added on Authorization headers for requests sent to the endpoint
     */
    private String authUsername;

    /**
     * The Basic Auth password to be added on Authorization headers for requests sent to the endpoint
     */
    private String authPassword;

    public SenderConfiguration() {
    }

    public SenderConfiguration withAgentHost(String agentHost) {
      this.agentHost = agentHost;
      return this;
    }

    public SenderConfiguration withAgentPort(Integer agentPort) {
      this.agentPort = agentPort;
      return this;
    }

    public SenderConfiguration withEndpoint(String endpoint) {
      this.endpoint = endpoint;
      return this;
    }

    public SenderConfiguration withAuthToken(String authToken) {
      this.authToken = authToken;
      return this;
    }

    public SenderConfiguration withAuthUsername(String username) {
      this.authUsername = username;
      return this;
    }

    public SenderConfiguration withAuthPassword(String password) {
      this.authPassword = password;
      return this;
    }

    /**
     * Returns a sender if one was given when creating the configuration, or attempts to create a sender based on the
     * configuration's state.
     * @return the sender passed via the constructor or a properly configured sender
     */
    public Sender getSender() {
      // if we have a sender, that's the one we return
      if (null != sender) {
        return sender;
      }

      return SenderResolver.resolve(this);
    }

    /**
     * Attempts to create a new {@link SenderConfiguration} based on the environment variables
     * @return a new sender configuration based on environment variables
     */
    public static SenderConfiguration fromEnv() {
      String agentHost = getProperty(JAEGER_AGENT_HOST);
      Integer agentPort = getPropertyAsInt(JAEGER_AGENT_PORT);

      String collectorEndpoint = getProperty(JAEGER_ENDPOINT);
      String authToken = getProperty(JAEGER_AUTH_TOKEN);
      String authUsername = getProperty(JAEGER_USER);
      String authPassword = getProperty(JAEGER_PASSWORD);

      return new SenderConfiguration()
              .withAgentHost(agentHost)
              .withAgentPort(agentPort)
              .withEndpoint(collectorEndpoint)
              .withAuthToken(authToken)
              .withAuthUsername(authUsername)
              .withAuthPassword(authPassword);
    }
  }

  private static String stringOrDefault(String value, String defaultValue) {
    return value != null && value.length() > 0 ? value : defaultValue;
  }

  private static Number numberOrDefault(Number value, Number defaultValue) {
    return value != null ? value : defaultValue;
  }

  private static String getProperty(String name) {
    return System.getProperty(name, System.getenv(name));
  }

  private static Integer getPropertyAsInt(String name) {
    String value = getProperty(name);
    if (value != null) {
      try {
        return Integer.parseInt(value);
      } catch (NumberFormatException e) {
        log.error("Failed to parse integer for property '" + name + "' with value '" + value + "'", e);
      }
    }
    return null;
  }

  private static Number getPropertyAsNum(String name) {
    String value = getProperty(name);
    if (value != null) {
      try {
        return NumberFormat.getInstance().parse(value);
      } catch (ParseException e) {
        log.error("Failed to parse number for property '" + name + "' with value '" + value + "'", e);
      }
    }
    return null;
  }

  /**
   * Gets the system property defined by the name , and returns a boolean value represented by
   * the name. This method defaults to returning false for a name that doesn't exist.
   * @param name The name of the system property
   */
  private static boolean getPropertyAsBool(String name) {
    return Boolean.valueOf(getProperty(name));
  }

  private static Map<String, String> tracerTagsFromEnv() {
    Map<String, String> tracerTagMaps = null;
    String tracerTags = getProperty(JAEGER_TAGS);
    if (tracerTags != null) {
      String[] tags = tracerTags.split("\\s*,\\s*");
      for (String tag : tags) {
        String[] tagValue = tag.split("\\s*=\\s*");
        if (tagValue.length == 2) {
          if (tracerTagMaps == null) {
            tracerTagMaps = new HashMap<String, String>();
          }
          tracerTagMaps.put(tagValue[0], resolveValue(tagValue[1]));
        } else {
          log.error("Tracer tag incorrectly formatted: " + tag);
        }
      }
    }
    return tracerTagMaps;
  }

  private static String resolveValue(String value) {
    if (value.startsWith("${") && value.endsWith("}")) {
      String[] ref = value.substring(2, value.length() - 1).split("\\s*:\\s*");
      if (ref.length > 0) {
        String propertyValue = getProperty(ref[0]);
        if (propertyValue == null && ref.length > 1) {
          propertyValue = ref[1];
        }
        return propertyValue;
      }
    }
    return value;
  }
}
