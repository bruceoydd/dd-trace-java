package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.api.datastreams.DataStreamsContext.create;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.DSM_CONCERN;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateNext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.closePrevious;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_IN;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.GROUP_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.KAFKA_CLUSTER_ID_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TOPIC_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TYPE_TAG;
import static datadog.trace.instrumentation.kafka_clients38.TextMapExtractAdapter.GETTER;
import static datadog.trace.instrumentation.kafka_clients38.TextMapInjectAdapter.SETTER;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.context.propagation.Propagator;
import datadog.context.propagation.Propagators;
import datadog.trace.api.Config;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.instrumentation.kafka_common.StreamingContext;
import datadog.trace.instrumentation.kafka_common.Utils;
import java.util.Iterator;
import java.util.LinkedHashMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TracingIterator implements Iterator<ConsumerRecord<?, ?>> {

  private static final Logger log = LoggerFactory.getLogger(TracingIterator.class);

  private final Iterator<ConsumerRecord<?, ?>> delegateIterator;
  private final CharSequence operationName;
  private final KafkaDecorator decorator;
  private final String group;
  private final String clusterId;
  private final String bootstrapServers;

  public TracingIterator(
      final Iterator<ConsumerRecord<?, ?>> delegateIterator,
      final CharSequence operationName,
      final KafkaDecorator decorator,
      String group,
      String clusterId,
      String bootstrapServers) {
    this.delegateIterator = delegateIterator;
    this.operationName = operationName;
    this.decorator = decorator;
    this.group = group;
    this.clusterId = clusterId;
    this.bootstrapServers = bootstrapServers;
  }

  @Override
  public boolean hasNext() {
    boolean moreRecords = delegateIterator.hasNext();
    if (!moreRecords) {
      // no more records, use this as a signal to close the last iteration scope
      closePrevious(true);
    }
    return moreRecords;
  }

  @Override
  public ConsumerRecord<?, ?> next() {
    final ConsumerRecord<?, ?> next = delegateIterator.next();
    startNewRecordSpan(next);
    return next;
  }

  protected void startNewRecordSpan(ConsumerRecord<?, ?> val) {
    try {
      closePrevious(true);
      AgentSpan span, queueSpan = null;
      if (val != null) {
        if (!Config.get().isKafkaClientPropagationDisabledForTopic(val.topic())) {
          final AgentSpanContext spanContext = propagate().extract(val.headers(), GETTER);
          long timeInQueueStart = GETTER.extractTimeInQueueStart(val.headers());
          if (timeInQueueStart == 0 || !KafkaDecorator.TIME_IN_QUEUE_ENABLED) {
            span = startSpan(operationName, spanContext);
          } else {
            queueSpan =
                startSpan(
                    KafkaDecorator.KAFKA_DELIVER,
                    spanContext,
                    MILLISECONDS.toMicros(timeInQueueStart));
            KafkaDecorator.BROKER_DECORATE.afterStart(queueSpan);
            KafkaDecorator.BROKER_DECORATE.onTimeInQueue(queueSpan, val);
            span = startSpan(operationName, queueSpan.context());
            KafkaDecorator.BROKER_DECORATE.beforeFinish(queueSpan);
            // The queueSpan will be finished after inner span has been activated to ensure that
            // spans are written out together by TraceStructureWriter when running in strict mode
          }

          LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>();
          sortedTags.put(DIRECTION_TAG, DIRECTION_IN);
          sortedTags.put(GROUP_TAG, group);
          if (clusterId != null) {
            sortedTags.put(KAFKA_CLUSTER_ID_TAG, clusterId);
          }
          sortedTags.put(TOPIC_TAG, val.topic());
          sortedTags.put(TYPE_TAG, "kafka");

          final long payloadSize =
              span.traceConfig().isDataStreamsEnabled() ? Utils.computePayloadSizeBytes(val) : 0;
          if (StreamingContext.STREAMING_CONTEXT.isDisabledForTopic(val.topic())) {
            AgentTracer.get()
                .getDataStreamsMonitoring()
                .setCheckpoint(span, create(sortedTags, val.timestamp(), payloadSize));
          } else {
            // when we're in a streaming context we want to consume only from source topics
            if (StreamingContext.STREAMING_CONTEXT.isSourceTopic(val.topic())) {
              // We have to inject the context to headers here,
              // since the data received from the source may leave the topology on
              // some other instance of the application, breaking the context propagation
              // for DSM users
              Propagator dsmPropagator = Propagators.forConcern(DSM_CONCERN);
              DataStreamsContext dsmContext = create(sortedTags, val.timestamp(), payloadSize);
              dsmPropagator.inject(span.with(dsmContext), val.headers(), SETTER);
            }
          }
        } else {
          span = startSpan(operationName, null);
        }
        if (val.value() == null) {
          span.setTag(InstrumentationTags.TOMBSTONE, true);
        }
        decorator.afterStart(span);
        decorator.onConsume(span, val, group, bootstrapServers);
        activateNext(span);
        if (null != queueSpan) {
          queueSpan.finish();
        }
      }
    } catch (final Exception e) {
      log.debug("Error starting new record span", e);
    }
  }

  @Override
  public void remove() {
    delegateIterator.remove();
  }
}
