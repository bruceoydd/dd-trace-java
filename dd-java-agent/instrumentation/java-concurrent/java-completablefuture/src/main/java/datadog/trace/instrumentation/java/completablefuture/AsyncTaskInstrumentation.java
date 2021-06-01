package datadog.trace.instrumentation.java.completablefuture;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.startTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.FORK_JOIN_TASK;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ForkJoinTask;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instruments classes used internally by {@code CompletableFuture} which are double instrumented as
 * {@code Runnable} and {@code ForkJoinTask} and can't be excluded because they can be used in
 * either context. This double instrumentation otherwsie leads to excess scope creation and
 * duplicate checkpoint emission.
 */
@AutoService(Instrumenter.class)
public final class AsyncTaskInstrumentation extends Instrumenter.Tracing
    implements ExcludeFilterProvider {

  private static final String[] CLASS_NAMES = {
    "java.util.concurrent.CompletableFuture$AsyncSupply",
    "java.util.concurrent.CompletableFuture$AsyncRun",
  };

  public AsyncTaskInstrumentation() {
    super("java_completablefuture", "java_concurrent");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return namedOneOf(CLASS_NAMES);
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("java.util.concurrent.ForkJoinTask", State.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(isConstructor(), getClass().getName() + "$Construct");
    transformation.applyAdvice(named("run"), getClass().getName() + "$Run");
    transformation.applyAdvice(named("cancel"), getClass().getName() + "$Cancel");
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    EnumMap<ExcludeFilter.ExcludeType, Collection<String>> excluded =
        new EnumMap<>(ExcludeFilter.ExcludeType.class);
    excluded.put(FORK_JOIN_TASK, Arrays.asList(CLASS_NAMES));
    excluded.put(RUNNABLE, Arrays.asList(CLASS_NAMES));
    return excluded;
  }

  public static class Construct {
    @Advice.OnMethodExit
    public static void construct(@Advice.This ForkJoinTask<?> zis) {
      TraceScope scope = activeScope();
      if (null != scope) {
        InstrumentationContext.get(ForkJoinTask.class, State.class)
            .putIfAbsent(zis, State.FACTORY)
            .captureAndSetContinuation(scope);
      }
    }
  }

  public static class Run {
    @Advice.OnMethodEnter
    public static TraceScope before(@Advice.This ForkJoinTask<?> zis) {
      return startTaskScope(InstrumentationContext.get(ForkJoinTask.class, State.class), zis);
    }

    @Advice.OnMethodExit
    public static void after(@Advice.Enter TraceScope scope) {
      endTaskScope(scope);
    }
  }

  public static class Cancel {
    @Advice.OnMethodExit
    public static <T> void cancel(@Advice.This ForkJoinTask<T> task) {
      State state = InstrumentationContext.get(ForkJoinTask.class, State.class).get(task);
      if (null != state) {
        state.closeContinuation();
      }
    }
  }
}
