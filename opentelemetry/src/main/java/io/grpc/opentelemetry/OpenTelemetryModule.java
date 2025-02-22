/*
 * Copyright 2023 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.opentelemetry;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.internal.GrpcUtil.IMPLEMENTATION_VERSION;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.Configurator;
import io.grpc.ConfiguratorRegistry;
import io.grpc.ExperimentalApi;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MetricSink;
import io.grpc.ServerBuilder;
import io.grpc.opentelemetry.internal.OpenTelemetryConstants;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterProvider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *  The entrypoint for OpenTelemetry metrics functionality in gRPC.
 *
 *  <p>OpenTelemetryModule uses {@link io.opentelemetry.api.OpenTelemetry} APIs for instrumentation.
 *  When no SDK is explicitly added no telemetry data will be collected. See
 *  {@link io.opentelemetry.sdk.OpenTelemetrySdk} for information on how to construct the SDK.
 *
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/10591")
public final class OpenTelemetryModule {

  private static final Supplier<Stopwatch> STOPWATCH_SUPPLIER = new Supplier<Stopwatch>() {
    @Override
    public Stopwatch get() {
      return Stopwatch.createUnstarted();
    }
  };

  private final OpenTelemetry openTelemetrySdk;
  private final MeterProvider meterProvider;
  private final Meter meter;
  private final Map<String, Boolean> enableMetrics;
  private final boolean disableDefault;
  private final OpenTelemetryMetricsResource resource;
  private final OpenTelemetryMetricsModule openTelemetryMetricsModule;
  private final List<String> optionalLabels;
  private final MetricSink sink;

  public static Builder newBuilder() {
    return new Builder();
  }

  private OpenTelemetryModule(Builder builder) {
    this.openTelemetrySdk = checkNotNull(builder.openTelemetrySdk, "openTelemetrySdk");
    this.meterProvider = checkNotNull(openTelemetrySdk.getMeterProvider(), "meterProvider");
    this.meter = this.meterProvider
        .meterBuilder(OpenTelemetryConstants.INSTRUMENTATION_SCOPE)
        .setInstrumentationVersion(IMPLEMENTATION_VERSION)
        .build();
    this.enableMetrics = ImmutableMap.copyOf(builder.enableMetrics);
    this.disableDefault = builder.disableAll;
    this.resource = createMetricInstruments(meter, enableMetrics, disableDefault);
    this.openTelemetryMetricsModule = new OpenTelemetryMetricsModule(STOPWATCH_SUPPLIER, resource);
    this.optionalLabels = ImmutableList.copyOf(builder.optionalLabels);
    this.sink = new OpenTelemetryMetricSink(meter, enableMetrics, disableDefault, optionalLabels);
  }

  @VisibleForTesting
  OpenTelemetry getOpenTelemetryInstance() {
    return this.openTelemetrySdk;
  }

  @VisibleForTesting
  MeterProvider getMeterProvider() {
    return this.meterProvider;
  }

  @VisibleForTesting
  Meter getMeter() {
    return this.meter;
  }

  @VisibleForTesting
  OpenTelemetryMetricsResource getResource() {
    return this.resource;
  }

  @VisibleForTesting
  Map<String, Boolean> getEnableMetrics() {
    return this.enableMetrics;
  }

  @VisibleForTesting
  List<String> getOptionalLabels() {
    return optionalLabels;
  }

  MetricSink getSink() {
    return sink;
  }

  /**
   * Registers OpenTelemetryModule globally, applying its configuration to all subsequently created
   * gRPC channels and servers.
   */
  public void registerGlobal() {
    ConfiguratorRegistry.getDefaultRegistry().setConfigurators(Collections.singletonList(
        new Configurator() {
          @Override
          public void configureChannelBuilder(ManagedChannelBuilder<?> channelBuilder) {
            OpenTelemetryModule.this.configureChannelBuilder(channelBuilder);
          }

          @Override
          public void configureServerBuilder(ServerBuilder<?> serverBuilder) {
            OpenTelemetryModule.this.configureServerBuilder(serverBuilder);
          }
        }));
  }

  /**
   * Configures the given {@link ManagedChannelBuilder} with OpenTelemetry metrics instrumentation.
   */
  public void configureChannelBuilder(ManagedChannelBuilder<?> builder) {
    builder.addMetricSink(sink);
    builder.intercept(openTelemetryMetricsModule.getClientInterceptor());
  }

  /**
   * Configures the given {@link ServerBuilder} with OpenTelemetry metrics instrumentation.
   *
   * @param serverBuilder the server builder to configure
   */
  public void configureServerBuilder(ServerBuilder<?> serverBuilder) {
    serverBuilder.addStreamTracerFactory(openTelemetryMetricsModule.getServerTracerFactory());
  }

  @VisibleForTesting
  static OpenTelemetryMetricsResource createMetricInstruments(Meter meter,
      Map<String, Boolean> enableMetrics, boolean disableDefault) {
    OpenTelemetryMetricsResource.Builder builder = OpenTelemetryMetricsResource.builder();

    if (isMetricEnabled("grpc.client.call.duration", enableMetrics, disableDefault)) {
      builder.clientCallDurationCounter(
          meter.histogramBuilder("grpc.client.call.duration")
              .setUnit("s")
              .setDescription(
                  "Time taken by gRPC to complete an RPC from application's perspective")
              .build());
    }

    if (isMetricEnabled("grpc.client.attempt.started", enableMetrics, disableDefault)) {
      builder.clientAttemptCountCounter(
          meter.counterBuilder("grpc.client.attempt.started")
              .setUnit("{attempt}")
              .setDescription("Number of client call attempts started")
              .build());
    }

    if (isMetricEnabled("grpc.client.attempt.duration", enableMetrics, disableDefault)) {
      builder.clientAttemptDurationCounter(
          meter.histogramBuilder(
                  "grpc.client.attempt.duration")
              .setUnit("s")
              .setDescription("Time taken to complete a client call attempt")
              .build());
    }

    if (isMetricEnabled("grpc.client.attempt.sent_total_compressed_message_size", enableMetrics,
        disableDefault)) {
      builder.clientTotalSentCompressedMessageSizeCounter(
          meter.histogramBuilder(
                  "grpc.client.attempt.sent_total_compressed_message_size")
              .setUnit("By")
              .setDescription("Compressed message bytes sent per client call attempt")
              .ofLongs()
              .build());
    }

    if (isMetricEnabled("grpc.client.attempt.rcvd_total_compressed_message_size", enableMetrics,
        disableDefault)) {
      builder.clientTotalReceivedCompressedMessageSizeCounter(
          meter.histogramBuilder(
                  "grpc.client.attempt.rcvd_total_compressed_message_size")
              .setUnit("By")
              .setDescription("Compressed message bytes received per call attempt")
              .ofLongs()
              .build());
    }

    if (isMetricEnabled("grpc.server.call.started", enableMetrics, disableDefault)) {
      builder.serverCallCountCounter(
          meter.counterBuilder("grpc.server.call.started")
              .setUnit("{call}")
              .setDescription("Number of server calls started")
              .build());
    }

    if (isMetricEnabled("grpc.server.call.duration", enableMetrics, disableDefault)) {
      builder.serverCallDurationCounter(
          meter.histogramBuilder("grpc.server.call.duration")
              .setUnit("s")
              .setDescription(
                  "Time taken to complete a call from server transport's perspective")
              .build());
    }

    if (isMetricEnabled("grpc.server.call.sent_total_compressed_message_size", enableMetrics,
        disableDefault)) {
      builder.serverTotalSentCompressedMessageSizeCounter(
          meter.histogramBuilder(
                  "grpc.server.call.sent_total_compressed_message_size")
              .setUnit("By")
              .setDescription("Compressed message bytes sent per server call")
              .ofLongs()
              .build());
    }

    if (isMetricEnabled("grpc.server.call.rcvd_total_compressed_message_size", enableMetrics,
        disableDefault)) {
      builder.serverTotalReceivedCompressedMessageSizeCounter(
          meter.histogramBuilder(
                  "grpc.server.call.rcvd_total_compressed_message_size")
              .setUnit("By")
              .setDescription("Compressed message bytes received per server call")
              .ofLongs()
              .build());
    }

    return builder.build();
  }

  static boolean isMetricEnabled(String metricName, Map<String, Boolean> enableMetrics,
      boolean disableDefault) {
    Boolean explicitlyEnabled = enableMetrics.get(metricName);
    if (explicitlyEnabled != null) {
      return explicitlyEnabled;
    }
    return OpenTelemetryMetricsModule.DEFAULT_PER_CALL_METRICS_SET.contains(metricName)
        && !disableDefault;
  }


  /**
   * Builder for configuring {@link OpenTelemetryModule}.
   */
  public static class Builder {
    private OpenTelemetry openTelemetrySdk = OpenTelemetry.noop();
    private final Collection<String> optionalLabels = new ArrayList<>();
    private final Map<String, Boolean> enableMetrics = new HashMap<>();
    private boolean disableAll;

    private Builder() {}

    /**
     * Sets the {@link io.opentelemetry.api.OpenTelemetry} entrypoint to use. This can be used to
     * configure OpenTelemetry by returning the instance created by a
     * {@link io.opentelemetry.sdk.OpenTelemetrySdkBuilder}.
     */
    public Builder sdk(OpenTelemetry sdk) {
      this.openTelemetrySdk = sdk;
      return this;
    }

    /**
     * Adds optionalLabelKey to all the metrics that can provide value for the
     * optionalLabelKey.
     */
    public Builder addOptionalLabel(String optionalLabelKey) {
      this.optionalLabels.add(optionalLabelKey);
      return this;
    }

    /**
     * Enables the specified metrics for collection and export. By default, only a subset of
     * metrics are enabled.
     */
    public Builder enableMetrics(Collection<String> enableMetrics) {
      for (String metric : enableMetrics) {
        this.enableMetrics.put(metric, true);
      }
      return this;
    }

    /**
     * Disables the specified metrics from being collected and exported.
     */
    public Builder disableMetrics(Collection<String> disableMetrics) {
      for (String metric : disableMetrics) {
        this.enableMetrics.put(metric, false);
      }
      return this;
    }

    /**
     * Disable all metrics. If set to true all metrics must be explicitly enabled.
     */
    public Builder disableAllMetrics() {
      this.enableMetrics.clear();
      this.disableAll = true;
      return this;
    }

    /**
     * Returns a new {@link OpenTelemetryModule} built with the configuration of this {@link
     * Builder}.
     */
    public OpenTelemetryModule build() {
      return new OpenTelemetryModule(this);
    }
  }
}
