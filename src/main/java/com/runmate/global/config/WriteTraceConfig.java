package com.runmate.global.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import org.aopalliance.intercept.MethodInterceptor;
import org.hibernate.Session;
import org.hibernate.SessionEventListener;
import org.hibernate.cfg.JdbcSettings;
import org.hibernate.engine.jdbc.connections.internal.DataSourceConnectionProvider;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.regex.Pattern;

@Configuration(proxyBeanMethods = false)
@Profile("load-test")
@ConditionalOnProperty(name = "runmate.load.trace-writes", havingValue = "true")
public class WriteTraceConfig {
    private static final Pattern TRACE_ID = Pattern.compile("(apply|cancel)_[0-9]+-[0-9]+");
    private static final Pattern WRITE_PATH = Pattern.compile("/api/v1/running-events/[0-9]+/participants(/cancel)?");
    private static final ThreadLocal<WriteRequest> TRACE = new ThreadLocal<>();

    static void recordGateWait(long nanos) {
        WriteRequest trace = TRACE.get();
        if (trace != null) {
            trace.gateWaitNanos = nanos;
        }
    }

    static void recordGateOccupancy(int occupancy) {
        WriteRequest trace = TRACE.get();
        if (trace != null) {
            trace.gateOccupancy = occupancy;
        }
    }

    @Bean
    HibernatePropertiesCustomizer timedCommitConnectionProvider() {
        return properties -> properties.put(JdbcSettings.CONNECTION_PROVIDER, new TimedConnectionProvider());
    }

    @Bean
    static BeanPostProcessor writeTiming(ObjectProvider<EntityManagerFactory> entityManagerFactory) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof HikariDataSource dataSource) {
                    dataSource.setMetricsTrackerFactory((pool, stats) -> new IMetricsTracker() {
                        @Override
                        public void recordConnectionAcquiredNanos(long nanos) {
                            WriteRequest trace = TRACE.get();
                            if (trace != null) {
                                trace.poolAcquireNanos += nanos;
                                trace.lastAcquireNanos = nanos;
                                trace.poolAcquisitions++;
                            }
                        }
                    });
                }
                return bean;
            }

            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!"runningEventRepository".equals(beanName)) {
                    return bean;
                }
                ProxyFactory proxy = new ProxyFactory(bean);
                proxy.addAdvice((MethodInterceptor) invocation -> {
                    WriteRequest trace = TRACE.get();
                    if (trace == null || !"findByIdForUpdate".equals(invocation.getMethod().getName())) {
                        return invocation.proceed();
                    }
                    var entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(
                            entityManagerFactory.getObject());
                    Session session = Objects.requireNonNull(entityManager).unwrap(Session.class);
                    trace.backendPid = session.doReturningWork(PostgresBackend::pid);
                    trace.writePoolAcquireNanos = trace.lastAcquireNanos;
                    trace.lockStartedEpochMillis = System.currentTimeMillis();
                    long started = System.nanoTime();
                    Object result = invocation.proceed();
                    long lockedAt = System.nanoTime();
                    trace.lockSelectNanos = lockedAt - started;
                    trace.lockFinishedEpochMillis = System.currentTimeMillis();
                    trace.lockReturnedAt = lockedAt;
                    session.addEventListeners(new PostLockSqlTiming(trace));
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            trace.beforeCommitAt = System.nanoTime();
                            trace.lockToBeforeCommitNanos = trace.beforeCommitAt - lockedAt;
                        }

                        @Override
                        public void afterCommit() {
                            trace.lockToCommitNanos = System.nanoTime() - lockedAt;
                        }
                    });
                    return result;
                });
                return proxy.getProxy();
            }
        };
    }

    private static final class TimedConnectionProvider extends DataSourceConnectionProvider {
        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            WriteRequest trace = TRACE.get();
            if (trace == null) {
                return connection;
            }
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("commit") && trace.lockReturnedAt != 0) {
                            long started = System.nanoTime();
                            trace.beforeCommitToJdbcNanos = started - trace.beforeCommitAt;
                            try {
                                connection.commit();
                                trace.jdbcCommits++;
                                return null;
                            } finally {
                                trace.jdbcCommitNanos += System.nanoTime() - started;
                            }
                        }
                        Object result = invokeJdbc(proxy, connection, method, args);
                        if (trace.lockReturnedAt != 0 && method.getName().equals("prepareStatement")
                                && WriteSql.enabled()) {
                            return timedStatement((PreparedStatement) result, (String) args[0], trace);
                        }
                        return result;
                    });
        }

        private static PreparedStatement timedStatement(PreparedStatement statement, String sql, WriteRequest trace) {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                        if (!method.getName().startsWith("execute")) {
                            return invokeJdbc(proxy, statement, method, args);
                        }
                        WriteSql event = new WriteSql();
                        event.traceId = trace.traceId;
                        event.backendPid = trace.backendPid;
                        event.phase = trace.beforeCommitAt == 0 ? "work" : "prepare";
                        event.sql = sql;
                        event.startedEpochMillis = System.currentTimeMillis();
                        long started = System.nanoTime();
                        try {
                            return invokeJdbc(proxy, statement, method, args);
                        } finally {
                            event.elapsedNanos = System.nanoTime() - started;
                            event.commit();
                        }
                    });
        }

        private static Object invokeJdbc(Object proxy, Object target, Method method, Object[] args) throws Throwable {
            if (method.getName().equals("equals")) {
                return proxy == args[0];
            }
            if (method.getName().equals("hashCode")) {
                return System.identityHashCode(proxy);
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }

    private static final class PostLockSqlTiming implements SessionEventListener {
        private final transient WriteRequest trace;
        private long sqlStartedAt;

        private PostLockSqlTiming(WriteRequest trace) {
            this.trace = trace;
        }

        @Override
        public void jdbcExecuteStatementStart() {
            sqlStartedAt = System.nanoTime();
        }

        @Override
        public void jdbcExecuteStatementEnd() {
            long elapsed = System.nanoTime() - sqlStartedAt;
            if (trace.beforeCommitAt == 0) {
                trace.workSqlNanos += elapsed;
                trace.workSqlStatements++;
            } else {
                trace.commitPreparationSqlNanos += elapsed;
                trace.commitPreparationSqlStatements++;
            }
        }

        @Override
        public void jdbcExecuteBatchStart() {
            jdbcExecuteStatementStart();
        }

        @Override
        public void jdbcExecuteBatchEnd() {
            jdbcExecuteStatementEnd();
        }
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> writeTraceFilter() {
        FlightRecorder.register(WriteRequest.class);
        FlightRecorder.register(WriteSql.class);
        var registration = new FilterRegistrationBean<OncePerRequestFilter>();
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String id = request.getHeader("X-Runmate-Trace-Id");
                if (id == null || !TRACE_ID.matcher(id).matches() || !"POST".equals(request.getMethod())
                        || !WRITE_PATH.matcher(request.getRequestURI()).matches()) {
                    chain.doFilter(request, response);
                    return;
                }
                WriteRequest trace = new WriteRequest();
                if (!trace.isEnabled()) {
                    chain.doFilter(request, response);
                    return;
                }
                trace.traceId = id;
                trace.begin();
                TRACE.set(trace);
                try {
                    chain.doFilter(request, response);
                } finally {
                    TRACE.remove();
                    trace.end();
                    trace.httpStatus = response.getStatus();
                    trace.commit();
                }
            }
        });
        return registration;
    }

    @Name("com.runmate.WriteSql")
    @StackTrace(false)
    static final class WriteSql extends Event {
        private static boolean enabled() {
            return jdk.jfr.EventType.getEventType(WriteSql.class).isEnabled();
        }

        String traceId;
        int backendPid;
        String phase;
        String sql;
        long startedEpochMillis;
        long elapsedNanos;
    }

    // PostgreSQL 드라이버는 runtimeOnly다. 기존 연결에서 PID를 얻어 추가 SQL을 피한다.
    private static final class PostgresBackend {
        private static final Class<?> TYPE = ClassUtils.resolveClassName("org.postgresql.PGConnection", null);
        private static final Method GET_PID = Objects.requireNonNull(ReflectionUtils.findMethod(TYPE, "getBackendPID"));

        private static int pid(Connection connection) throws SQLException {
            try {
                return (int) GET_PID.invoke(connection.unwrap(TYPE));
            } catch (ReflectiveOperationException e) {
                throw new SQLException("Cannot read the PostgreSQL backend PID", e);
            }
        }
    }

    @Name("com.runmate.WriteRequest")
    @StackTrace(false)
    static final class WriteRequest extends Event {
        String traceId;
        int httpStatus;
        int backendPid;
        int poolAcquisitions;
        long poolAcquireNanos;
        long writePoolAcquireNanos;
        long gateWaitNanos;
        int gateOccupancy;
        long lockStartedEpochMillis;
        long lockFinishedEpochMillis;
        long lockSelectNanos;
        long lockToCommitNanos;
        long lockToBeforeCommitNanos;
        long beforeCommitToJdbcNanos;
        long jdbcCommitNanos;
        long workSqlNanos;
        long commitPreparationSqlNanos;
        int workSqlStatements;
        int commitPreparationSqlStatements;
        int jdbcCommits;
        private transient long lockReturnedAt;
        private transient long beforeCommitAt;
        private transient long lastAcquireNanos;
    }
}
