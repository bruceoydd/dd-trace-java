package datadog.trace.bootstrap.instrumentation.api;

import static datadog.trace.api.ConfigDefaults.DEFAULT_ASYNC_PROPAGATING;
import static java.util.Collections.emptyList;

import datadog.trace.api.ConfigDefaults;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.EndpointCheckpointer;
import datadog.trace.api.EndpointTracker;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.api.experimental.DataStreamsCheckpointer;
import datadog.trace.api.experimental.DataStreamsContextCarrier;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.api.internal.InternalTracer;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingRule;
import datadog.trace.api.scopemanager.ScopeListener;
import datadog.trace.context.TraceScope;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class AgentTracer {
  private static final String DEFAULT_INSTRUMENTATION_NAME = "datadog";

  // Implicit parent
  /** Deprecated. Use {@link #startSpan(String, CharSequence)} instead. */
  @Deprecated
  public static AgentSpan startSpan(final CharSequence spanName) {
    return startSpan(DEFAULT_INSTRUMENTATION_NAME, spanName);
  }

  /** @see TracerAPI#startSpan(String, CharSequence) */
  public static AgentSpan startSpan(final String instrumentationName, final CharSequence spanName) {
    return get().startSpan(instrumentationName, spanName);
  }

  // Implicit parent
  /** Deprecated. Use {@link #startSpan(String, CharSequence, long)} instead. */
  @Deprecated
  public static AgentSpan startSpan(final CharSequence spanName, final long startTimeMicros) {
    return startSpan(DEFAULT_INSTRUMENTATION_NAME, spanName, startTimeMicros);
  }

  /** @see TracerAPI#startSpan(String, CharSequence, long) */
  public static AgentSpan startSpan(
      final String instrumentationName, final CharSequence spanName, final long startTimeMicros) {
    return get().startSpan(instrumentationName, spanName, startTimeMicros);
  }

  // Explicit parent
  /** Deprecated. Use {@link #startSpan(String, CharSequence, AgentSpanContext)} instead. */
  @Deprecated
  public static AgentSpan startSpan(final CharSequence spanName, final AgentSpanContext parent) {
    return startSpan(DEFAULT_INSTRUMENTATION_NAME, spanName, parent);
  }

  /** @see TracerAPI#startSpan(String, CharSequence, AgentSpanContext) */
  public static AgentSpan startSpan(
      final String instrumentationName,
      final CharSequence spanName,
      final AgentSpanContext parent) {
    return get().startSpan(instrumentationName, spanName, parent);
  }

  // Explicit parent
  /** Deprecated. Use {@link #startSpan(String, CharSequence, AgentSpanContext, long)} instead. */
  @Deprecated
  public static AgentSpan startSpan(
      final CharSequence spanName, final AgentSpanContext parent, final long startTimeMicros) {
    return startSpan(DEFAULT_INSTRUMENTATION_NAME, spanName, parent, startTimeMicros);
  }

  /** @see TracerAPI#startSpan(String, CharSequence, AgentSpanContext, long) */
  public static AgentSpan startSpan(
      final String instrumentationName,
      final CharSequence spanName,
      final AgentSpanContext parent,
      final long startTimeMicros) {
    return get().startSpan(instrumentationName, spanName, parent, startTimeMicros);
  }

  public static AgentScope activateSpan(final AgentSpan span) {
    return get().activateSpan(span, ScopeSource.INSTRUMENTATION, DEFAULT_ASYNC_PROPAGATING);
  }

  public static AgentScope activateSpan(final AgentSpan span, final boolean isAsyncPropagating) {
    return get().activateSpan(span, ScopeSource.INSTRUMENTATION, isAsyncPropagating);
  }

  public static AgentScope.Continuation captureSpan(final AgentSpan span) {
    return get().captureSpan(span);
  }

  /**
   * Closes the immediately previous iteration scope. Should be called before creating a new span
   * for {@link #activateNext(AgentSpan)}.
   */
  public static void closePrevious(final boolean finishSpan) {
    get().closePrevious(finishSpan);
  }

  /**
   * Activates a new iteration scope; closes automatically after a fixed period.
   *
   * @see datadog.trace.api.config.TracerConfig#SCOPE_ITERATION_KEEP_ALIVE
   */
  public static AgentScope activateNext(final AgentSpan span) {
    return get().activateNext(span);
  }

  public static TraceConfig traceConfig(final AgentSpan span) {
    return null != span ? span.traceConfig() : traceConfig();
  }

  public static TraceConfig traceConfig() {
    return get().captureTraceConfig();
  }

  public static AgentSpan activeSpan() {
    return get().activeSpan();
  }

  public static AgentScope activeScope() {
    return get().activeScope();
  }

  public static AgentScope.Continuation capture() {
    final AgentScope activeScope = activeScope();
    return activeScope == null ? null : activeScope.capture();
  }

  /**
   * Checks whether asynchronous propagation is enabled, meaning this context will propagate across
   * asynchronous boundaries.
   *
   * @return {@code true} if asynchronous propagation is enabled, {@code false} otherwise.
   */
  public static boolean isAsyncPropagationEnabled() {
    return get().isAsyncPropagationEnabled();
  }

  /**
   * Enables or disables asynchronous propagation for the active span.
   *
   * <p>Asynchronous propagation is enabled by default from {@link
   * ConfigDefaults#DEFAULT_ASYNC_PROPAGATING}.
   *
   * @param asyncPropagationEnabled @{@code true} to enable asynchronous propagation, {@code false}
   *     to disable it.
   */
  public static void setAsyncPropagationEnabled(boolean asyncPropagationEnabled) {
    get().setAsyncPropagationEnabled(asyncPropagationEnabled);
  }

  public static AgentPropagation propagate() {
    return get().propagate();
  }

  public static AgentSpan noopSpan() {
    return get().noopSpan();
  }

  public static AgentSpan blackholeSpan() {
    return get().blackholeSpan();
  }

  public static final TracerAPI NOOP_TRACER = new NoopTracerAPI();

  private static volatile TracerAPI provider = NOOP_TRACER;

  public static boolean isRegistered() {
    return provider != NOOP_TRACER;
  }

  public static synchronized void registerIfAbsent(final TracerAPI tracer) {
    if (tracer != null && tracer != NOOP_TRACER) {
      provider = tracer;
    }
  }

  public static synchronized void forceRegister(TracerAPI tracer) {
    provider = tracer;
  }

  public static TracerAPI get() {
    return provider;
  }

  // Not intended to be constructed.
  private AgentTracer() {}

  public interface TracerAPI
      extends datadog.trace.api.Tracer, InternalTracer, EndpointCheckpointer, ScopeStateAware {

    /**
     * Create and start a new span.
     *
     * @param instrumentationName The instrumentation creating the span.
     * @param spanName The span operation name.
     * @return The new started span.
     */
    AgentSpan startSpan(String instrumentationName, CharSequence spanName);

    /**
     * Create and start a new span with a given start time.
     *
     * @param instrumentationName The instrumentation creating the span.
     * @param spanName The span operation name.
     * @param startTimeMicros The span start time, in microseconds.
     * @return The new started span.
     */
    AgentSpan startSpan(String instrumentationName, CharSequence spanName, long startTimeMicros);

    /**
     * Create and start a new span with an explicit parent.
     *
     * @param instrumentationName The instrumentation creating the span.
     * @param spanName The span operation name.
     * @param parent The parent span context.
     * @return The new started span.
     */
    AgentSpan startSpan(String instrumentationName, CharSequence spanName, AgentSpanContext parent);

    /**
     * Create and start a new span with an explicit parent and a given start time.
     *
     * @param instrumentationName The instrumentation creating the span.
     * @param spanName The span operation name.
     * @param parent The parent span context.
     * @param startTimeMicros The span start time, in microseconds.
     * @return The new started span.
     */
    AgentSpan startSpan(
        String instrumentationName,
        CharSequence spanName,
        AgentSpanContext parent,
        long startTimeMicros);

    AgentScope activateSpan(AgentSpan span, ScopeSource source);

    AgentScope activateSpan(AgentSpan span, ScopeSource source, boolean isAsyncPropagating);

    AgentScope.Continuation captureSpan(AgentSpan span);

    void closePrevious(boolean finishSpan);

    AgentScope activateNext(AgentSpan span);

    AgentSpan activeSpan();

    AgentScope activeScope();

    AgentPropagation propagate();

    AgentSpan noopSpan();

    AgentSpan blackholeSpan();

    /** Deprecated. Use {@link #buildSpan(String, CharSequence)} instead. */
    @Deprecated
    default SpanBuilder buildSpan(CharSequence spanName) {
      return buildSpan(DEFAULT_INSTRUMENTATION_NAME, spanName);
    }

    SpanBuilder buildSpan(String instrumentationName, CharSequence spanName);

    void close();

    /**
     * Attach a scope listener to the global scope manager
     *
     * @param listener listener to attach
     */
    void addScopeListener(ScopeListener listener);

    SubscriptionService getSubscriptionService(RequestContextSlot slot);

    CallbackProvider getCallbackProvider(RequestContextSlot slot);

    CallbackProvider getUniversalCallbackProvider();

    AgentSpanContext notifyExtensionStart(Object event);

    void notifyExtensionEnd(AgentSpan span, Object result, boolean isError);

    AgentDataStreamsMonitoring getDataStreamsMonitoring();

    String getTraceId(AgentSpan span);

    String getSpanId(AgentSpan span);

    TraceConfig captureTraceConfig();

    ProfilingContextIntegration getProfilingContext();

    AgentHistogram newHistogram(double relativeAccuracy, int maxNumBins);

    /**
     * Sets the new service name to be used as a default.
     *
     * @param serviceName The service name to use as default.
     */
    void updatePreferredServiceName(String serviceName);
  }

  public interface SpanBuilder {
    AgentSpan start();

    SpanBuilder asChildOf(AgentSpanContext toContext);

    SpanBuilder ignoreActiveSpan();

    SpanBuilder withTag(String key, String value);

    SpanBuilder withTag(String key, boolean value);

    SpanBuilder withTag(String key, Number value);

    SpanBuilder withTag(String tag, Object value);

    SpanBuilder withStartTimestamp(long microseconds);

    SpanBuilder withServiceName(String serviceName);

    SpanBuilder withResourceName(String resourceName);

    SpanBuilder withErrorFlag();

    SpanBuilder withSpanType(CharSequence spanType);

    <T> SpanBuilder withRequestContextData(RequestContextSlot slot, T data);

    SpanBuilder withLink(AgentSpanLink link);

    SpanBuilder withSpanId(long spanId);
  }

  static class NoopTracerAPI implements TracerAPI {

    protected NoopTracerAPI() {}

    @Override
    public AgentSpan startSpan(final String instrumentationName, final CharSequence spanName) {
      return NoopAgentSpan.INSTANCE;
    }

    @Override
    public AgentSpan startSpan(
        final String instrumentationName, final CharSequence spanName, final long startTimeMicros) {
      return NoopAgentSpan.INSTANCE;
    }

    @Override
    public AgentSpan startSpan(
        final String instrumentationName,
        final CharSequence spanName,
        final AgentSpanContext parent) {
      return NoopAgentSpan.INSTANCE;
    }

    @Override
    public AgentSpan startSpan(
        final String instrumentationName,
        final CharSequence spanName,
        final AgentSpanContext parent,
        final long startTimeMicros) {
      return NoopAgentSpan.INSTANCE;
    }

    @Override
    public AgentScope activateSpan(final AgentSpan span, final ScopeSource source) {
      return NoopAgentScope.INSTANCE;
    }

    @Override
    public AgentScope activateSpan(
        final AgentSpan span, final ScopeSource source, final boolean isAsyncPropagating) {
      return NoopAgentScope.INSTANCE;
    }

    @Override
    public AgentScope.Continuation captureSpan(final AgentSpan span) {
      return NoopContinuation.INSTANCE;
    }

    @Override
    public boolean isAsyncPropagationEnabled() {
      return false;
    }

    @Override
    public void setAsyncPropagationEnabled(boolean asyncPropagationEnabled) {}

    @Override
    public void closePrevious(final boolean finishSpan) {}

    @Override
    public AgentScope activateNext(final AgentSpan span) {
      return NoopAgentScope.INSTANCE;
    }

    @Override
    public AgentSpan activeSpan() {
      return NoopAgentSpan.INSTANCE;
    }

    @Override
    public AgentScope activeScope() {
      return null;
    }

    @Override
    public AgentPropagation propagate() {
      return NoopAgentPropagation.INSTANCE;
    }

    @Override
    public AgentSpan noopSpan() {
      return NoopAgentSpan.INSTANCE;
    }

    @Override
    public AgentSpan blackholeSpan() {
      return NoopAgentSpan.INSTANCE; // no-op tracer stays no-op
    }

    @Override
    public SpanBuilder buildSpan(final String instrumentationName, final CharSequence spanName) {
      return null;
    }

    @Override
    public void close() {}

    @Override
    public void addScopeListener(
        Runnable afterScopeActivatedCallback, Runnable afterScopeClosedCallback) {}

    @Override
    public void flush() {}

    @Override
    public void flushMetrics() {}

    @Override
    public ProfilingContextIntegration getProfilingContext() {
      return ProfilingContextIntegration.NoOp.INSTANCE;
    }

    @Override
    public TraceSegment getTraceSegment() {
      return null;
    }

    @Override
    public String getTraceId() {
      return null;
    }

    @Override
    public String getSpanId() {
      return null;
    }

    @Override
    public String getTraceId(AgentSpan span) {
      return null;
    }

    @Override
    public String getSpanId(AgentSpan span) {
      return null;
    }

    @Override
    public boolean addTraceInterceptor(final TraceInterceptor traceInterceptor) {
      return false;
    }

    @Override
    public TraceScope muteTracing() {
      return NoopAgentScope.INSTANCE;
    }

    @Override
    public DataStreamsCheckpointer getDataStreamsCheckpointer() {
      return getDataStreamsMonitoring();
    }

    @Override
    public void addScopeListener(final ScopeListener listener) {}

    @Override
    public SubscriptionService getSubscriptionService(RequestContextSlot slot) {
      return SubscriptionService.SubscriptionServiceNoop.INSTANCE;
    }

    @Override
    public CallbackProvider getCallbackProvider(RequestContextSlot slot) {
      return CallbackProvider.CallbackProviderNoop.INSTANCE;
    }

    @Override
    public CallbackProvider getUniversalCallbackProvider() {
      return CallbackProvider.CallbackProviderNoop.INSTANCE;
    }

    @Override
    public void onRootSpanFinished(AgentSpan root, EndpointTracker tracker) {}

    @Override
    public EndpointTracker onRootSpanStarted(AgentSpan root) {
      return EndpointTracker.NO_OP;
    }

    @Override
    public AgentSpanContext notifyExtensionStart(Object event) {
      return null;
    }

    @Override
    public void notifyExtensionEnd(AgentSpan span, Object result, boolean isError) {}

    @Override
    public ScopeState newScopeState() {
      return null;
    }

    @Override
    public AgentDataStreamsMonitoring getDataStreamsMonitoring() {
      return NoopAgentDataStreamsMonitoring.INSTANCE;
    }

    @Override
    public TraceConfig captureTraceConfig() {
      return NoopTraceConfig.INSTANCE;
    }

    @Override
    public AgentHistogram newHistogram(double relativeAccuracy, int maxNumBins) {
      return NoopAgentHistogram.INSTANCE;
    }

    @Override
    public void updatePreferredServiceName(String serviceName) {
      // no ops
    }
  }

  public static final class BlackholeAgentSpan extends NoopAgentSpan {
    private final DDTraceId ddTraceId;

    public BlackholeAgentSpan(final DDTraceId ddTraceId) {
      this.ddTraceId = ddTraceId;
    }

    @Override
    public boolean isSameTrace(final AgentSpan otherSpan) {
      return otherSpan != null
          && ((ddTraceId != null && ddTraceId.equals(otherSpan.getTraceId()))
              || otherSpan.getTraceId() == null);
    }

    @Override
    public DDTraceId getTraceId() {
      return ddTraceId;
    }

    @Override
    public AgentSpanContext context() {
      return BlackholeContext.INSTANCE;
    }
  }

  /**
   * Represents a remote span from an extracted span context.
   *
   * <p>Tags and baggage access are inefficient and only supported as remediation for products
   * storing propagated information into span context, until they migrate to the new context API.
   */
  static final class ExtractedSpan extends AgentTracer.NoopAgentSpan {
    private final AgentSpanContext spanContext;

    ExtractedSpan(AgentSpanContext spanContext) {
      super();
      this.spanContext = spanContext;
    }

    @Override
    public DDTraceId getTraceId() {
      return this.spanContext.getTraceId();
    }

    @Override
    public long getSpanId() {
      return this.spanContext.getSpanId();
    }

    @Override
    public Object getTag(final String tag) {
      if (this.spanContext instanceof TagContext) {
        return ((TagContext) this.spanContext).getTags().get(tag);
      }
      return null;
    }

    @Override
    public Map<String, Object> getTags() {
      if (this.spanContext instanceof TagContext) {
        Map<String, String> tags = ((TagContext) this.spanContext).getTags();
        //noinspection unchecked
        return (Map<String, Object>) (Map<?, ?>) tags;
      }
      return Collections.emptyMap();
    }

    @Override
    public String getBaggageItem(final String key) {
      Iterable<Map.Entry<String, String>> baggage = this.spanContext.baggageItems();
      for (Map.Entry<String, String> stringStringEntry : baggage) {
        if (stringStringEntry.getKey().equals(key)) {
          return stringStringEntry.getValue();
        }
      }
      return null;
    }

    @Override
    public AgentSpanContext context() {
      return this.spanContext;
    }

    @Override
    public boolean isSameTrace(AgentSpan otherSpan) {
      return null != otherSpan && getTraceId().equals(otherSpan.getTraceId());
    }

    @Override
    public String toString() {
      return "ExtractedSpan{spanContext=" + this.spanContext + '}';
    }
  }

  public static class NoopAgentSpan implements AgentSpan {
    public static final NoopAgentSpan INSTANCE = new NoopAgentSpan();

    private NoopAgentSpan() {}

    @Override
    public DDTraceId getTraceId() {
      return DDTraceId.ZERO;
    }

    @Override
    public long getSpanId() {
      return DDSpanId.ZERO;
    }

    @Override
    public AgentSpan setTag(final String key, final boolean value) {
      return this;
    }

    @Override
    public void setRequestBlockingAction(Flow.Action.RequestBlockingAction rba) {}

    @Override
    public Flow.Action.RequestBlockingAction getRequestBlockingAction() {
      return null;
    }

    @Override
    public AgentSpan setTag(final String tag, final Number value) {
      return this;
    }

    @Override
    public boolean isError() {
      return false;
    }

    @Override
    public AgentSpan setTag(final String key, final int value) {
      return this;
    }

    @Override
    public AgentSpan setTag(final String key, final long value) {
      return this;
    }

    @Override
    public AgentSpan setTag(final String key, final double value) {
      return this;
    }

    @Override
    public AgentSpan setTag(final String key, final Object value) {
      return this;
    }

    @Override
    public AgentSpan setAllTags(Map<String, ?> map) {
      return this;
    }

    @Override
    public AgentSpan setMetric(final CharSequence key, final int value) {
      return this;
    }

    @Override
    public AgentSpan setMetric(final CharSequence key, final long value) {
      return this;
    }

    @Override
    public AgentSpan setMetric(final CharSequence key, final double value) {
      return this;
    }

    @Override
    public Object getTag(final String key) {
      return null;
    }

    @Override
    public long getStartTime() {
      return 0;
    }

    @Override
    public long getDurationNano() {
      return 0;
    }

    @Override
    public String getOperationName() {
      return null;
    }

    @Override
    public AgentSpan setOperationName(final CharSequence serviceName) {
      return this;
    }

    @Override
    public String getServiceName() {
      return null;
    }

    @Override
    public AgentSpan setServiceName(final String serviceName) {
      return this;
    }

    @Override
    public CharSequence getResourceName() {
      return null;
    }

    @Override
    public AgentSpan setResourceName(final CharSequence resourceName) {
      return this;
    }

    @Override
    public AgentSpan setResourceName(final CharSequence resourceName, byte priority) {
      return this;
    }

    @Override
    public boolean eligibleForDropping() {
      return true;
    }

    @Override
    public RequestContext getRequestContext() {
      return RequestContext.Noop.INSTANCE;
    }

    @Override
    public Integer forceSamplingDecision() {
      return null;
    }

    @Override
    public AgentSpan setSamplingPriority(int newPriority, int samplingMechanism) {
      return this;
    }

    @Override
    public Integer getSamplingPriority() {
      return (int) PrioritySampling.UNSET;
    }

    @Override
    public AgentSpan setSamplingPriority(final int newPriority) {
      return this;
    }

    @Override
    public String getSpanType() {
      return null;
    }

    @Override
    public AgentSpan setSpanType(final CharSequence type) {
      return this;
    }

    @Override
    public Map<String, Object> getTags() {
      return Collections.emptyMap();
    }

    @Override
    public AgentSpan setTag(final String key, final String value) {
      return this;
    }

    @Override
    public AgentSpan setTag(final String key, final CharSequence value) {
      return this;
    }

    @Override
    public AgentSpan setError(final boolean error) {
      return this;
    }

    @Override
    public AgentSpan setError(boolean error, byte priority) {
      return this;
    }

    @Override
    public AgentSpan setMeasured(boolean measured) {
      return this;
    }

    @Override
    public AgentSpan getRootSpan() {
      return this;
    }

    @Override
    public AgentSpan setErrorMessage(final String errorMessage) {
      return this;
    }

    @Override
    public AgentSpan addThrowable(final Throwable throwable) {
      return this;
    }

    @Override
    public AgentSpan addThrowable(Throwable throwable, byte errorPriority) {
      return this;
    }

    @Override
    public AgentSpan setHttpStatusCode(int statusCode) {
      return this;
    }

    @Override
    public short getHttpStatusCode() {
      return 0;
    }

    @Override
    public AgentSpan getLocalRootSpan() {
      return this;
    }

    @Override
    public boolean isSameTrace(final AgentSpan otherSpan) {
      return otherSpan == INSTANCE;
    }

    @Override
    public AgentSpanContext context() {
      return NoopContext.INSTANCE;
    }

    @Override
    public String getBaggageItem(final String key) {
      return null;
    }

    @Override
    public AgentSpan setBaggageItem(final String key, final String value) {
      return this;
    }

    @Override
    public void finish() {}

    @Override
    public void finish(final long finishMicros) {}

    @Override
    public void finishWithDuration(final long durationNanos) {}

    @Override
    public void beginEndToEnd() {}

    @Override
    public void finishWithEndToEnd() {}

    @Override
    public boolean phasedFinish() {
      return false;
    }

    @Override
    public void publish() {}

    @Override
    public String getSpanName() {
      return "";
    }

    @Override
    public void setSpanName(final CharSequence spanName) {}

    @Override
    public boolean hasResourceName() {
      return false;
    }

    @Override
    public byte getResourceNamePriority() {
      return Byte.MAX_VALUE;
    }

    @Override
    public TraceConfig traceConfig() {
      return NoopTraceConfig.INSTANCE;
    }

    @Override
    public void addLink(AgentSpanLink link) {}

    @Override
    public AgentSpan setMetaStruct(String field, Object value) {
      return this;
    }

    @Override
    public boolean isOutbound() {
      return false;
    }

    @Override
    public boolean isRequiresPostProcessing() {
      return false;
    }

    @Override
    public void setRequiresPostProcessing(boolean requiresPostProcessing) {}
  }

  public static final class NoopAgentScope implements AgentScope {
    public static final NoopAgentScope INSTANCE = new NoopAgentScope();

    private NoopAgentScope() {}

    @Override
    public AgentSpan span() {
      return NoopAgentSpan.INSTANCE;
    }

    @Override
    public byte source() {
      return 0;
    }

    @Override
    public void setAsyncPropagation(final boolean value) {}

    @Override
    public AgentScope.Continuation capture() {
      return NoopContinuation.INSTANCE;
    }

    @Override
    public AgentScope.Continuation captureConcurrent() {
      return NoopContinuation.INSTANCE;
    }

    @Override
    public void close() {}

    @Override
    public boolean isAsyncPropagating() {
      return false;
    }
  }

  static class NoopAgentPropagation implements AgentPropagation {
    static final NoopAgentPropagation INSTANCE = new NoopAgentPropagation();

    @Override
    public <C> void inject(final AgentSpan span, final C carrier, final Setter<C> setter) {}

    @Override
    public <C> void inject(
        final AgentSpanContext context, final C carrier, final Setter<C> setter) {}

    @Override
    public <C> void inject(
        AgentSpan span, C carrier, Setter<C> setter, TracePropagationStyle style) {}

    @Override
    public <C> void injectPathwayContext(
        AgentSpan span, C carrier, Setter<C> setter, LinkedHashMap<String, String> sortedTags) {}

    @Override
    public <C> void injectPathwayContext(
        AgentSpan span,
        C carrier,
        Setter<C> setter,
        LinkedHashMap<String, String> sortedTags,
        long defaultTimestamp,
        long payloadSizeBytes) {}

    @Override
    public <C> void injectPathwayContextWithoutSendingStats(
        AgentSpan span, C carrier, Setter<C> setter, LinkedHashMap<String, String> sortedTags) {}

    @Override
    public <C> AgentSpanContext.Extracted extract(final C carrier, final ContextVisitor<C> getter) {
      return NoopContext.INSTANCE;
    }
  }

  static class NoopContinuation implements AgentScope.Continuation {
    static final NoopContinuation INSTANCE = new NoopContinuation();

    @Override
    public AgentScope activate() {
      return NoopAgentScope.INSTANCE;
    }

    @Override
    public void cancel() {}

    @Override
    public AgentSpan getSpan() {
      return NoopAgentSpan.INSTANCE;
    }
  }

  public static final class BlackholeContext extends NoopContext {
    public static final BlackholeContext INSTANCE = new BlackholeContext();

    private BlackholeContext() {}
  }

  public static class NoopContext implements AgentSpanContext.Extracted {
    public static final NoopContext INSTANCE = new NoopContext();

    private NoopContext() {}

    @Override
    public DDTraceId getTraceId() {
      return DDTraceId.ZERO;
    }

    @Override
    public long getSpanId() {
      return DDSpanId.ZERO;
    }

    @Override
    public AgentTraceCollector getTraceCollector() {
      return NoopAgentTraceCollector.INSTANCE;
    }

    @Override
    public int getSamplingPriority() {
      return PrioritySampling.UNSET;
    }

    @Override
    public Iterable<Map.Entry<String, String>> baggageItems() {
      return emptyList();
    }

    @Override
    public PathwayContext getPathwayContext() {
      return NoopPathwayContext.INSTANCE;
    }

    @Override
    public List<AgentSpanLink> getTerminatedContextLinks() {
      return emptyList();
    }

    @Override
    public String getForwarded() {
      return null;
    }

    @Override
    public String getFastlyClientIp() {
      return null;
    }

    @Override
    public String getCfConnectingIp() {
      return null;
    }

    @Override
    public String getCfConnectingIpv6() {
      return null;
    }

    @Override
    public String getXForwardedProto() {
      return null;
    }

    @Override
    public String getXForwardedHost() {
      return null;
    }

    @Override
    public String getXForwardedPort() {
      return null;
    }

    @Override
    public String getForwardedFor() {
      return null;
    }

    @Override
    public String getXForwardedFor() {
      return null;
    }

    @Override
    public String getXClusterClientIp() {
      return null;
    }

    @Override
    public String getXRealIp() {
      return null;
    }

    @Override
    public String getXClientIp() {
      return null;
    }

    @Override
    public String getUserAgent() {
      return null;
    }

    @Override
    public String getTrueClientIp() {
      return null;
    }

    @Override
    public String getCustomIpHeader() {
      return null;
    }
  }

  public static class NoopAgentTraceCollector implements AgentTraceCollector {
    public static final NoopAgentTraceCollector INSTANCE = new NoopAgentTraceCollector();

    @Override
    public void registerContinuation(final AgentScope.Continuation continuation) {}

    @Override
    public void cancelContinuation(final AgentScope.Continuation continuation) {}
  }

  public static class NoopAgentDataStreamsMonitoring implements AgentDataStreamsMonitoring {
    public static final NoopAgentDataStreamsMonitoring INSTANCE =
        new NoopAgentDataStreamsMonitoring();

    @Override
    public void trackBacklog(LinkedHashMap<String, String> sortedTags, long value) {}

    @Override
    public void setCheckpoint(
        AgentSpan span,
        LinkedHashMap<String, String> sortedTags,
        long defaultTimestamp,
        long payloadSizeBytes) {}

    @Override
    public PathwayContext newPathwayContext() {
      return NoopPathwayContext.INSTANCE;
    }

    @Override
    public void add(StatsPoint statsPoint) {}

    @Override
    public int trySampleSchema(String topic) {
      return 0;
    }

    @Override
    public boolean canSampleSchema(String topic) {
      return false;
    }

    @Override
    public Schema getSchema(String schemaName, SchemaIterator iterator) {
      return null;
    }

    @Override
    public void setProduceCheckpoint(String type, String target) {}

    @Override
    public void setThreadServiceName(String serviceName) {}

    @Override
    public void clearThreadServiceName() {}

    @Override
    public void setConsumeCheckpoint(
        String type, String source, DataStreamsContextCarrier carrier) {}

    @Override
    public void setProduceCheckpoint(
        String type, String target, DataStreamsContextCarrier carrier) {}
  }

  public static class NoopPathwayContext implements PathwayContext {
    public static final NoopPathwayContext INSTANCE = new NoopPathwayContext();

    @Override
    public boolean isStarted() {
      return false;
    }

    @Override
    public long getHash() {
      return 0L;
    }

    @Override
    public void setCheckpoint(
        LinkedHashMap<String, String> sortedTags,
        Consumer<StatsPoint> pointConsumer,
        long defaultTimestamp,
        long payloadSizeBytes) {}

    @Override
    public void setCheckpoint(
        LinkedHashMap<String, String> sortedTags,
        Consumer<StatsPoint> pointConsumer,
        long defaultTimestamp) {}

    @Override
    public void setCheckpoint(
        LinkedHashMap<String, String> sortedTags, Consumer<StatsPoint> pointConsumer) {}

    @Override
    public void saveStats(StatsPoint point) {}

    @Override
    public StatsPoint getSavedStats() {
      return null;
    }

    @Override
    public String encode() {
      return null;
    }
  }

  public static class NoopAgentHistogram implements AgentHistogram {
    public static final NoopAgentHistogram INSTANCE = new NoopAgentHistogram();

    @Override
    public double getCount() {
      return 0;
    }

    @Override
    public boolean isEmpty() {
      return true;
    }

    @Override
    public void accept(double value) {}

    @Override
    public void accept(double value, double count) {}

    @Override
    public double getValueAtQuantile(double quantile) {
      return 0;
    }

    @Override
    public double getMinValue() {
      return 0;
    }

    @Override
    public double getMaxValue() {
      return 0;
    }

    @Override
    public void clear() {}

    @Override
    public ByteBuffer serialize() {
      return null;
    }
  }

  /** TraceConfig when there is no tracer; this is not the same as a default config. */
  public static final class NoopTraceConfig implements TraceConfig {
    public static final NoopTraceConfig INSTANCE = new NoopTraceConfig();

    @Override
    public boolean isTraceEnabled() {
      return false;
    }

    @Override
    public boolean isRuntimeMetricsEnabled() {
      return false;
    }

    @Override
    public boolean isLogsInjectionEnabled() {
      return false;
    }

    @Override
    public boolean isDataStreamsEnabled() {
      return false;
    }

    @Override
    public Map<String, String> getServiceMapping() {
      return Collections.emptyMap();
    }

    @Override
    public Map<String, String> getRequestHeaderTags() {
      return Collections.emptyMap();
    }

    @Override
    public Map<String, String> getResponseHeaderTags() {
      return Collections.emptyMap();
    }

    @Override
    public Map<String, String> getBaggageMapping() {
      return Collections.emptyMap();
    }

    @Override
    public Double getTraceSampleRate() {
      return null;
    }

    @Override
    public Map<String, String> getTracingTags() {
      return Collections.emptyMap();
    }

    @Override
    public String getPreferredServiceName() {
      return null;
    }

    @Override
    public List<? extends SamplingRule.SpanSamplingRule> getSpanSamplingRules() {
      return Collections.emptyList();
    }

    @Override
    public List<? extends SamplingRule.TraceSamplingRule> getTraceSamplingRules() {
      return Collections.emptyList();
    }
  }
}
