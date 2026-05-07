package tech.ydb.core.metrics;

import java.util.Objects;

import io.grpc.ExperimentalApi;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;

import tech.ydb.core.Status;

@ExperimentalApi("YDB Meter is experimental and API may change without notice")
public final class OpenTelemetryMeter implements Meter {
    private static final String DEFAULT_SCOPE = "tech.ydb.sdk";

    private static final AttributeKey<String> OPERATION_NAME = AttributeKey.stringKey("operation.name");
    private static final AttributeKey<String> STATUS_CODE = AttributeKey.stringKey("db.response.status_code");
    private static final AttributeKey<String> POOL_NAME = AttributeKey.stringKey("ydb.query.session.pool.name");
    private static final AttributeKey<String> SESSION_STATE = AttributeKey.stringKey("ydb.query.session.state");

    private final io.opentelemetry.api.metrics.Meter meter;
    private final Attributes baseAttributes;

    private final DoubleHistogram operationDuration;
    private final LongCounter operationFailed;
    private final DoubleHistogram sessionCreateTime;
    private final LongCounter sessionPendingRequests;
    private final LongCounter sessionTimeouts;

    private OpenTelemetryMeter(io.opentelemetry.api.metrics.Meter meter,
                               String database, String host, int port) {
        this.meter = Objects.requireNonNull(meter, "meter is null");

        this.baseAttributes = Attributes.of(
                AttributeKey.stringKey("db.system.name"), "ydb",
                AttributeKey.stringKey("db.namespace"), database,
                AttributeKey.stringKey("server.address"), host,
                AttributeKey.longKey("server.port"), (long) port
        );

        this.operationDuration = meter.histogramBuilder("ydb.client.operation.duration")
                .setUnit("s")
                .setDescription("Duration of a single client operation attempt (ExecuteQuery, Commit, Rollback).")
                .build();

        this.operationFailed = meter.counterBuilder("ydb.client.operation.failed")
                .setUnit("{operation}")
                .setDescription("Number of failed client operation attempts.")
                .build();

        this.sessionCreateTime = meter.histogramBuilder("ydb.query.session.create_time")
                .setUnit("s")
                .setDescription("Time spent creating a new session.")
                .build();

        this.sessionPendingRequests = meter.counterBuilder("ydb.query.session.pending_requests")
                .setUnit("{request}")
                .setDescription("Number of session-acquire requests that had to wait for a free session.")
                .build();

        this.sessionTimeouts = meter.counterBuilder("ydb.query.session.timeouts")
                .setUnit("{timeout}")
                .setDescription("Number of session-acquire timeouts.")
                .build();
    }

    public static OpenTelemetryMeter fromOpenTelemetry(OpenTelemetry openTelemetry,
                                                       String database, String host, int port) {
        Objects.requireNonNull(openTelemetry, "openTelemetry is null");
        return new OpenTelemetryMeter(openTelemetry.getMeter(DEFAULT_SCOPE), database, host, port);
    }

    public static OpenTelemetryMeter createGlobal(String database, String host, int port) {
        return fromOpenTelemetry(GlobalOpenTelemetry.get(), database, host, port);
    }

    @Override
    public void recordOperationDuration(String operationName, long durationNanos) {
        operationDuration.record(toSeconds(durationNanos), withOperation(operationName));
    }

    @Override
    public void recordOperationFailed(String operationName, Status status) {
        if (status == null || status.isSuccess()) {
            return;
        }
        Attributes attrs = baseAttributes.toBuilder()
                .put(OPERATION_NAME, operationName)
                .put(STATUS_CODE, status.getCode().toString())
                .build();
        operationFailed.add(1L, attrs);
    }

    @Override
    public void registerSessionPool(String poolName, SessionPoolObserver observer) {
        Attributes idle = baseAttributes.toBuilder()
                .put(POOL_NAME, poolName)
                .put(SESSION_STATE, "idle")
                .build();
        Attributes used = baseAttributes.toBuilder()
                .put(POOL_NAME, poolName)
                .put(SESSION_STATE, "used")
                .build();
        Attributes pool = baseAttributes.toBuilder()
                .put(POOL_NAME, poolName)
                .build();

        meter.gaugeBuilder("ydb.query.session.count")
                .ofLongs()
                .setUnit("{session}")
                .setDescription("Current number of sessions in the pool by state.")
                .buildWithCallback(measurement -> {
                    measurement.record(observer.getIdleCount(), idle);
                    measurement.record(observer.getUsedCount(), used);
                });

        meter.gaugeBuilder("ydb.query.session.min")
                .ofLongs()
                .setUnit("{session}")
                .setDescription("Configured minimum size of the session pool.")
                .buildWithCallback(measurement -> measurement.record(observer.getMinSize(), pool));

        meter.gaugeBuilder("ydb.query.session.max")
                .ofLongs()
                .setUnit("{session}")
                .setDescription("Configured maximum size of the session pool.")
                .buildWithCallback(measurement -> measurement.record(observer.getMaxSize(), pool));
    }

    @Override
    public void recordSessionCreateTime(String poolName, long durationNanos) {
        sessionCreateTime.record(toSeconds(durationNanos), withPool(poolName));
    }

    @Override
    public void incrementSessionPendingRequests(String poolName) {
        sessionPendingRequests.add(1L, withPool(poolName));
    }

    @Override
    public void incrementSessionTimeouts(String poolName) {
        sessionTimeouts.add(1L, withPool(poolName));
    }

    private Attributes withOperation(String operationName) {
        return baseAttributes.toBuilder().put(OPERATION_NAME, operationName).build();
    }

    private Attributes withPool(String poolName) {
        return baseAttributes.toBuilder().put(POOL_NAME, poolName).build();
    }

    private static double toSeconds(long durationNanos) {
        return durationNanos / 1_000_000_000.0;
    }
}
