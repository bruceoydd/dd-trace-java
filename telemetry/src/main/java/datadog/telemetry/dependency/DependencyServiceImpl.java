package datadog.telemetry.dependency;

import datadog.trace.api.Config;
import datadog.trace.util.AgentTaskScheduler;
import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service that detects app dependencies from classloading by using a no-op class-file transformer
 */
public class DependencyServiceImpl implements DependencyService, Runnable {

  private static final Logger log = LoggerFactory.getLogger(DependencyServiceImpl.class);

  private final DependencyResolverQueue resolverQueue = new DependencyResolverQueue();

  private final BlockingQueue<Dependency> newDependencies = new LinkedBlockingQueue<>();

  private AgentTaskScheduler.Scheduled<Runnable> scheduledTask;

  public void schedulePeriodicResolution() {
    scheduledTask =
        AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
            AgentTaskScheduler.RunnableTask.INSTANCE, this, 0, 1000L, TimeUnit.MILLISECONDS);
  }

  public void resolveOneDependency() {
    List<Dependency> dependencies = resolverQueue.pollDependency();
    if (!dependencies.isEmpty()) {
      for (Dependency dependency : dependencies) {
        log.debug("Resolved dependency {}", dependency.getName());
        newDependencies.add(dependency);
      }
    }
  }

  /**
   * Registers this service as a no-op class file transformer.
   *
   * @param instrumentation instrumentation instance to register on
   */
  public void installOn(Instrumentation instrumentation) {
    instrumentation.addTransformer(new LocationsCollectingTransformer(this));
  }

  @Override
  public Collection<Dependency> drainDeterminedDependencies() {
    List<Dependency> list = new LinkedList<>();
    int drained = newDependencies.drainTo(list);
    if (drained > 0) {
      return list;
    }
    return Collections.emptyList();
  }

  @Override
  public void addURL(URL url) {
    resolverQueue.queueURI(convertToURI(url));
  }

  private URI convertToURI(URL location) {
    URI uri = null;

    if (location.getProtocol().equals("vfs")) {
      // resolve jboss virtual file system
      try {
        uri = JbossVirtualFileHelper.getJbossVfsPath(location);
      } catch (RuntimeException rte) {
        log.debug("Error in call to getJbossVfsPath", rte);
        return null;
      }
    }

    if (uri == null) {
      try {
        uri = location.toURI();
        // Prepend missing slash to make opaque file URIs heirarchical.
        // This is to fix malformed Windows URIs e.g., file:c:/temp/lib.jar
        if (uri.isOpaque() && "file".equals(uri.getScheme())) {
          String oldUri = uri.toASCIIString();
          log.debug("Opaque URI found: '{}'", oldUri);
          if (Config.get().isTelemetryConvertOpaqueUriEnabled()) {
            uri = URI.create(uri.toASCIIString().replaceFirst("file:", "file:/"));
            log.warn(
                "Opaque URI '{}' changed to hierarchical URI '{}'", oldUri, uri.toASCIIString());
          }
        }
      } catch (URISyntaxException e) {
        log.warn("Error converting URL to URI", e);
        // silently ignored
      }
    }
    return uri;
  }

  @Override
  public void run() {
    resolveOneDependency();
  }

  @Override
  public void stop() {
    if (scheduledTask != null) {
      scheduledTask.cancel();
      scheduledTask = null;
    }
  }
}
