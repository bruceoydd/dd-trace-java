package datadog.trace.instrumentation.netty40.client;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import io.netty.handler.codec.http.HttpHeaders;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class NettyResponseInjectAdapter implements AgentPropagation.Setter<HttpHeaders> {

  public static final NettyResponseInjectAdapter SETTER = new NettyResponseInjectAdapter();

  @Override
  public void set(final HttpHeaders headers, final String key, final String value) {
    headers.set(key, value);
  }
}
