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

    private static final AttributeKey<String> DATABASE = AttributeKey.stringKey("database");
    private static final AttributeKey<String> ENDPOINT = AttributeKey.stringKey("endpoint");
    private static final AttributeKey<String> OPERATION_NAME = AttributeKey.stringKey("operation.name");
    private static final AttributeKey<String> STATUS_CODE = AttributeKey.stringKey("status_code");
    private static final AttributeKey<String> POOL_NAME = AttributeKey.stringKey("ydb.query.session.pool.name");
    private static final AttributeKey<String> SESSION_STATE = AttributeKey.stringKey("ydb.query.session.state");

    private final io.opentelemetry.api.metrics.Meter meter;
    private final Attributes operationBaseAttributes;

    private final DoubleHistogram operationDuration;
    private final LongCounter operationFailed;
    private final DoubleHistogram sessionCreateTime;
    private final LongCounter sessionPendingRequests;
    private final LongCounter sessionTimeouts;

    private OpenTelemetryMeter(io.opentelemetry.api.metrics.Meter meter, String database, String endpoint) {
        this.meter = Objects.requireNonNull(meter, "meter is null");

        this.operationBaseAttributes = Attributes.of(
                DATABASE, database,
                ENDPOINT, endpoint
        );

        this.operationDuration = meter.histogramBuilder("ydb.client.operation.duration")
                .setUnit("s")
                .setDescription("Latency of each actual ExecuteQuery / Commit / Rollback attempt.")
                .build();

        this.operationFailed = meter.counterBuilder("ydb.client.operation.failed")
                .setUnit("{operation}")
                .setDescription("Unsuccessful operation attempts.")
                .build();

        this.sessionCreateTime = meter.histogramBuilder("ydb.query.session.create_time")
                .setUnit("s")
                .setDescription("Session creation cost (CreateSession + first AttachStream message).")
                .build();

        this.sessionPendingRequests = meter.counterBuilder("ydb.query.session.pending_requests")
                .setUnit("{request}")
                .setDescription("Increments when a caller starts waiting; use rate for wait pressure.")
                .build();

        this.sessionTimeouts = meter.counterBuilder("ydb.query.session.timeouts")
                .setUnit("{timeout}")
                .setDescription("Session acquisition timeouts.")
                .build();
    }

    public static OpenTelemetryMeter fromOpenTelemetry(OpenTelemetry openTelemetry,
                                                       String database, String endpoint) {
        Objects.requireNonNull(openTelemetry, "openTelemetry is null");
        return new OpenTelemetryMeter(openTelemetry.getMeter(DEFAULT_SCOPE), database, endpoint);
    }

    public static OpenTelemetryMeter createGlobal(String database, String endpoint) {
        return fromOpenTelemetry(GlobalOpenTelemetry.get(), database, endpoint);
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
        Attributes attrs = operationBaseAttributes.toBuilder()
                .put(OPERATION_NAME, operationName)
                .put(STATUS_CODE, status.getCode().toString())
                .build();
        operationFailed.add(1L, attrs);
    }

    @Override
    public void registerSessionPool(String poolName, SessionPoolObserver observer) {
        Attributes idle = Attributes.of(POOL_NAME, poolName, SESSION_STATE, "idle");
        Attributes used = Attributes.of(POOL_NAME, poolName, SESSION_STATE, "used");
        Attributes pool = Attributes.of(POOL_NAME, poolName);

        meter.gaugeBuilder("ydb.query.session.count")
                .ofLongs()
                .setUnit("{session}")
                .setDescription("Current pool session counts.")
                .buildWithCallback(measurement -> {
                    measurement.record(observer.getIdleCount(), idle);
                    measurement.record(observer.getUsedCount(), used);
                });

        meter.gaugeBuilder("ydb.query.session.min")
                .ofLongs()
                .setUnit("{session}")
                .setDescription("Configured MinPoolSize.")
                .buildWithCallback(measurement -> measurement.record(observer.getMinSize(), pool));

        meter.gaugeBuilder("ydb.query.session.max")
                .ofLongs()
                .setUnit("{session}")
                .setDescription("Configured MaxPoolSize.")
                .buildWithCallback(measurement -> measurement.record(observer.getMaxSize(), pool));
    }

    @Override
    public void recordSessionCreateTime(String poolName, long durationNanos) {
        sessionCreateTime.record(toSeconds(durationNanos), poolAttributes(poolName));
    }

    @Override
    public void incrementSessionPendingRequests(String poolName) {
        sessionPendingRequests.add(1L, poolAttributes(poolName));
    }

    @Override
    public void incrementSessionTimeouts(String poolName) {
        sessionTimeouts.add(1L, poolAttributes(poolName));
    }

    private Attributes withOperation(String operationName) {
        return operationBaseAttributes.toBuilder().put(OPERATION_NAME, operationName).build();
    }

    private static Attributes poolAttributes(String poolName) {
        return Attributes.of(POOL_NAME, poolName);
    }

    private static double toSeconds(long durationNanos) {
        return durationNanos / 1_000_000_000.0;
    }
}
