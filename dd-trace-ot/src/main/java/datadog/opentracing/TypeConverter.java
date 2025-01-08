package datadog.opentracing;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AttachableWrapper;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;

// Centralized place to do conversions
class TypeConverter {
  private final LogHandler logHandler;
  private final OTSpan noopSpanWrapper;
  private final OTSpanContext noopContextWrapper;
  private final OTScopeManager.OTScope noopScopeWrapper;

  public TypeConverter(final LogHandler logHandler) {
    this.logHandler = logHandler;
    noopSpanWrapper = new OTSpan(AgentTracer.NoopAgentSpan.INSTANCE, this, logHandler);
    noopContextWrapper = new OTSpanContext(AgentTracer.NoopContext.INSTANCE);
    noopScopeWrapper = new OTScopeManager.OTScope(AgentTracer.NoopAgentScope.INSTANCE, false, this);
  }

  public AgentSpan toAgentSpan(final Span span) {
    if (span == null) {
      return null;
    } else if (span instanceof OTSpan) {
      return ((OTSpan) span).asAgentSpan();
    } else {
      // NOOP Span
      return AgentTracer.NoopAgentSpan.INSTANCE;
    }
  }

  public OTSpan toSpan(final AgentSpan agentSpan) {
    if (agentSpan == null) {
      return null;
    }
    if (agentSpan instanceof AttachableWrapper) {
      AttachableWrapper attachableSpanWrapper = (AttachableWrapper) agentSpan;
      Object wrapper = attachableSpanWrapper.getWrapper();
      if (wrapper instanceof OTSpan) {
        return (OTSpan) wrapper;
      }
      OTSpan spanWrapper = new OTSpan(agentSpan, this, logHandler);
      attachableSpanWrapper.attachWrapper(spanWrapper);
      return spanWrapper;
    }
    if (agentSpan == AgentTracer.NoopAgentSpan.INSTANCE) {
      return noopSpanWrapper;
    }
    return new OTSpan(agentSpan, this, logHandler);
  }

  public Scope toScope(final AgentScope scope, final boolean finishSpanOnClose) {
    if (scope == null) {
      return null;
    }
    if (scope instanceof AttachableWrapper) {
      AttachableWrapper attachableScopeWrapper = (AttachableWrapper) scope;
      Object wrapper = attachableScopeWrapper.getWrapper();
      if (wrapper instanceof OTScopeManager.OTScope) {
        OTScopeManager.OTScope attachedScopeWrapper = (OTScopeManager.OTScope) wrapper;
        if (attachedScopeWrapper.isFinishSpanOnClose() == finishSpanOnClose) {
          return (Scope) wrapper;
        }
      }
      Scope otScope = new OTScopeManager.OTScope(scope, finishSpanOnClose, this);
      attachableScopeWrapper.attachWrapper(otScope);
      return otScope;
    }
    if (scope == AgentTracer.NoopAgentScope.INSTANCE) {
      return noopScopeWrapper;
    }
    return new OTScopeManager.OTScope(scope, finishSpanOnClose, this);
  }

  public SpanContext toSpanContext(final AgentSpanContext context) {
    if (context == null) {
      return null;
    }
    // avoid a new SpanContext wrapper allocation for the noop context
    if (context == AgentTracer.NoopContext.INSTANCE) {
      return noopContextWrapper;
    }
    return new OTSpanContext(context);
  }

  public AgentSpanContext toContext(final SpanContext spanContext) {
    if (spanContext == null) {
      return null;
    } else if (spanContext instanceof OTSpanContext) {
      return ((OTSpanContext) spanContext).getDelegate();
    } else {
      return AgentTracer.NoopContext.INSTANCE;
    }
  }
}
