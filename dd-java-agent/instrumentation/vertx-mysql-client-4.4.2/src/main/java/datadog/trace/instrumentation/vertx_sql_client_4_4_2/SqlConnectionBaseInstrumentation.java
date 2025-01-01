package datadog.trace.instrumentation.vertx_sql_client_4_4_2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import java.util.HashMap;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class SqlConnectionBaseInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public SqlConnectionBaseInstrumentation() {
    super("vertx", "vertx-sql-client");
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>();
    contextStores.put("io.vertx.sqlclient.SqlClient", DBInfo.class.getName());
    contextStores.put("io.vertx.mysqlclient.impl.MySQLConnectionFactory", DBInfo.class.getName());
    return contextStores;
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.sqlclient.impl.SqlConnectionBase";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {

    transformer.applyAdvice(
        isConstructor()
            .and(takesArguments(4))
            .and(takesArgument(1, named("io.vertx.sqlclient.spi.ConnectionFactory"))),
        packageName + ".SqlConnectionBaseConstructorAdvice");
  }
}
