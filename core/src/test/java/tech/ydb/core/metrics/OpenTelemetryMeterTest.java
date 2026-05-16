package tech.ydb.core.metrics;

import java.util.Collection;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import tech.ydb.core.Status;
import tech.ydb.core.StatusCode;

public class OpenTelemetryMeterTest {

    private static final String DATABASE = "/local";
    private static final String ENDPOINT = "localhost:2136";
    private static final String POOL = "default";

    private InMemoryMetricReader reader;
    private OpenTelemetryMeter meter;

    @Before
    public void setUp() {
        reader = InMemoryMetricReader.create();
        SdkMeterProvider provider = SdkMeterProvider.builder()
                .registerMetricReader(reader)
                .build();
        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setMeterProvider(provider)
                .build();
        meter = OpenTelemetryMeter.fromOpenTelemetry(openTelemetry, DATABASE, ENDPOINT);
    }

    @After
    public void tearDown() throws Exception {
        reader.close();
    }

    @Test
    public void testRecordOperationDuration() {
        meter.recordOperationDuration("ExecuteQuery", 500_000_000L); // 0.5s

        MetricData metric = findMetric("ydb.client.operation.duration");
        Assert.assertNotNull(metric);

        HistogramPointData point = findHistogramPoint(metric, "ExecuteQuery");
        Assert.assertNotNull(point);
        Assert.assertTrue(point.getSum() > 0);
        Assert.assertEquals(1, point.getCount());
    }

    @Test
    public void testRecordOperationDurationNanosToSeconds() {
        meter.recordOperationDuration("ExecuteQuery", 1_000_000_000L); // ровно 1s

        MetricData metric = findMetric("ydb.client.operation.duration");
        Assert.assertNotNull(metric);

        HistogramPointData point = findHistogramPoint(metric, "ExecuteQuery");
        Assert.assertNotNull(point);
        Assert.assertEquals(1.0, point.getSum(), 0.001);
    }

    @Test
    public void testRecordOperationDurationAccumulates() {
        meter.recordOperationDuration("ExecuteQuery", 100_000_000L);
        meter.recordOperationDuration("ExecuteQuery", 200_000_000L);
        meter.recordOperationDuration("ExecuteQuery", 300_000_000L);

        HistogramPointData point = findHistogramPoint(findMetric("ydb.client.operation.duration"), "ExecuteQuery");
        Assert.assertNotNull(point);
        Assert.assertEquals(3, point.getCount());
        Assert.assertEquals(0.6, point.getSum(), 0.001);
    }

    @Test
    public void testRecordOperationDurationDifferentOperations() {
        meter.recordOperationDuration("ExecuteQuery", 100_000_000L);
        meter.recordOperationDuration("Commit", 200_000_000L);
        meter.recordOperationDuration("Rollback", 300_000_000L);

        MetricData metric = findMetric("ydb.client.operation.duration");
        Assert.assertNotNull(findHistogramPoint(metric, "ExecuteQuery"));
        Assert.assertNotNull(findHistogramPoint(metric, "Commit"));
        Assert.assertNotNull(findHistogramPoint(metric, "Rollback"));
    }

    @Test
    public void testRecordOperationDurationHasDatabaseAndEndpointAttributes() {
        meter.recordOperationDuration("ExecuteQuery", 100_000_000L);

        MetricData metric = findMetric("ydb.client.operation.duration");
        Assert.assertNotNull(metric);

        boolean hasDatabase = metric.getHistogramData().getPoints().stream()
                .anyMatch(p -> DATABASE.equals(p.getAttributes().get(
                        io.opentelemetry.api.common.AttributeKey.stringKey("database"))));
        boolean hasEndpoint = metric.getHistogramData().getPoints().stream()
                .anyMatch(p -> ENDPOINT.equals(p.getAttributes().get(
                        io.opentelemetry.api.common.AttributeKey.stringKey("endpoint"))));

        Assert.assertTrue(hasDatabase);
        Assert.assertTrue(hasEndpoint);
    }

    @Test
    public void testRecordOperationFailed() {
        meter.recordOperationFailed("ExecuteQuery", Status.of(StatusCode.NOT_FOUND));

        MetricData metric = findMetric("ydb.client.operation.failed");
        Assert.assertNotNull(metric);

        long total = metric.getLongSumData().getPoints().stream()
                .mapToLong(LongPointData::getValue).sum();
        Assert.assertEquals(1, total);
    }

    @Test
    public void testRecordOperationFailedIgnoresSuccess() {
        meter.recordOperationFailed("ExecuteQuery", Status.SUCCESS);

        MetricData metric = findMetric("ydb.client.operation.failed");
        Assert.assertNull(metric);
    }

    @Test
    public void testRecordOperationFailedIgnoresNull() {
        meter.recordOperationFailed("ExecuteQuery", null);

        MetricData metric = findMetric("ydb.client.operation.failed");
        Assert.assertNull(metric);
    }

    @Test
    public void testRecordOperationFailedAccumulates() {
        meter.recordOperationFailed("ExecuteQuery", Status.of(StatusCode.NOT_FOUND));
        meter.recordOperationFailed("ExecuteQuery", Status.of(StatusCode.NOT_FOUND));
        meter.recordOperationFailed("ExecuteQuery", Status.of(StatusCode.GENERIC_ERROR));

        long total = findMetric("ydb.client.operation.failed").getLongSumData().getPoints().stream()
                .mapToLong(LongPointData::getValue).sum();
        Assert.assertEquals(3, total);
    }

    @Test
    public void testRecordOperationFailedHasStatusCodeAttribute() {
        meter.recordOperationFailed("ExecuteQuery", Status.of(StatusCode.NOT_FOUND));

        MetricData metric = findMetric("ydb.client.operation.failed");
        Assert.assertNotNull(metric);

        boolean hasStatusCode = metric.getLongSumData().getPoints().stream()
                .anyMatch(p -> p.getAttributes().get(
                        io.opentelemetry.api.common.AttributeKey.stringKey("status_code")) != null);
        Assert.assertTrue(hasStatusCode);
    }

    @Test
    public void testRecordSessionCreateTime() {
        meter.recordSessionCreateTime(POOL, 300_000_000L);

        MetricData metric = findMetric("ydb.query.session.create_time");
        Assert.assertNotNull(metric);

        HistogramPointData point = metric.getHistogramData().getPoints().iterator().next();
        Assert.assertEquals(0.3, point.getSum(), 0.001);
        Assert.assertEquals(1, point.getCount());
    }

    @Test
    public void testRecordSessionCreateTimeAccumulates() {
        meter.recordSessionCreateTime(POOL, 100_000_000L);
        meter.recordSessionCreateTime(POOL, 200_000_000L);

        MetricData metric = findMetric("ydb.query.session.create_time");
        HistogramPointData point = metric.getHistogramData().getPoints().iterator().next();
        Assert.assertEquals(2, point.getCount());
        Assert.assertEquals(0.3, point.getSum(), 0.001);
    }

    @Test
    public void testIncrementSessionPendingRequests() {
        meter.incrementSessionPendingRequests(POOL);

        MetricData metric = findMetric("ydb.query.session.pending_requests");
        Assert.assertNotNull(metric);

        long total = metric.getLongSumData().getPoints().stream()
                .mapToLong(LongPointData::getValue).sum();
        Assert.assertEquals(1, total);
    }

    @Test
    public void testIncrementSessionPendingRequestsAccumulates() {
        meter.incrementSessionPendingRequests(POOL);
        meter.incrementSessionPendingRequests(POOL);
        meter.incrementSessionPendingRequests(POOL);

        long total = findMetric("ydb.query.session.pending_requests").getLongSumData().getPoints().stream()
                .mapToLong(LongPointData::getValue).sum();
        Assert.assertEquals(3, total);
    }

    @Test
    public void testIncrementSessionTimeouts() {
        meter.incrementSessionTimeouts(POOL);

        MetricData metric = findMetric("ydb.query.session.timeouts");
        Assert.assertNotNull(metric);

        long total = metric.getLongSumData().getPoints().stream()
                .mapToLong(LongPointData::getValue).sum();
        Assert.assertEquals(1, total);
    }

    @Test
    public void testIncrementSessionTimeoutsAccumulates() {
        meter.incrementSessionTimeouts(POOL);
        meter.incrementSessionTimeouts(POOL);

        long total = findMetric("ydb.query.session.timeouts").getLongSumData().getPoints().stream()
                .mapToLong(LongPointData::getValue).sum();
        Assert.assertEquals(2, total);
    }

    @Test
    public void testRegisterSessionPool() {
        SessionPoolObserver observer = new SessionPoolObserver() {
            @Override public int getMinSize() { return 2; }
            @Override public int getMaxSize() { return 10; }
            @Override public int getIdleCount() { return 3; }
            @Override public int getUsedCount() { return 4; }
        };

        meter.registerSessionPool(POOL, observer);

        Collection<MetricData> metrics = reader.collectAllMetrics();
        Assert.assertTrue(metrics.stream().anyMatch(m -> m.getName().equals("ydb.query.session.count")));
        Assert.assertTrue(metrics.stream().anyMatch(m -> m.getName().equals("ydb.query.session.min")));
        Assert.assertTrue(metrics.stream().anyMatch(m -> m.getName().equals("ydb.query.session.max")));
    }

    @Test
    public void testRegisterSessionPoolMinValue() {
        meter.registerSessionPool(POOL, new SessionPoolObserver() {
            @Override public int getMinSize() { return 5; }
            @Override public int getMaxSize() { return 20; }
            @Override public int getIdleCount() { return 0; }
            @Override public int getUsedCount() { return 0; }
        });

        MetricData min = reader.collectAllMetrics().stream()
                .filter(m -> m.getName().equals("ydb.query.session.min"))
                .findFirst().orElse(null);
        Assert.assertNotNull(min);
        long minVal = min.getLongGaugeData().getPoints().stream()
                .mapToLong(LongPointData::getValue).findFirst().orElse(-1);
        Assert.assertEquals(5, minVal);
    }

    @Test
    public void testRegisterSessionPoolMaxValue() {
        meter.registerSessionPool(POOL, new SessionPoolObserver() {
            @Override public int getMinSize() { return 0; }
            @Override public int getMaxSize() { return 50; }
            @Override public int getIdleCount() { return 0; }
            @Override public int getUsedCount() { return 0; }
        });

        MetricData max = reader.collectAllMetrics().stream()
                .filter(m -> m.getName().equals("ydb.query.session.max"))
                .findFirst().orElse(null);
        Assert.assertNotNull(max);
        long maxVal = max.getLongGaugeData().getPoints().stream()
                .mapToLong(LongPointData::getValue).findFirst().orElse(-1);
        Assert.assertEquals(50, maxVal);
    }

    @Test
    public void testRecordRetryDuration() {
        meter.recordRetryDuration("ydb.RunWithRetry", 700_000_000L);

        MetricData metric = findMetric("ydb.client.retry.duration");
        Assert.assertNotNull(metric);

        HistogramPointData point = metric.getHistogramData().getPoints().iterator().next();
        Assert.assertEquals(0.7, point.getSum(), 0.001);
        Assert.assertEquals(1, point.getCount());
    }

    @Test
    public void testRecordRetryAttempts() {
        meter.recordRetryAttempts("ydb.RunWithRetry", 3);

        MetricData metric = findMetric("ydb.client.retry.attempts");
        Assert.assertNotNull(metric);

        HistogramPointData point = metric.getHistogramData().getPoints().iterator().next();
        Assert.assertEquals(3, point.getSum(), 0.001);
        Assert.assertEquals(1, point.getCount());
    }

    @Test
    public void testRecordRetryAttemptsAccumulates() {
        meter.recordRetryAttempts("ydb.RunWithRetry", 1);
        meter.recordRetryAttempts("ydb.RunWithRetry", 2);
        meter.recordRetryAttempts("ydb.RunWithRetry", 3);

        HistogramPointData point = findMetric("ydb.client.retry.attempts")
                .getHistogramData().getPoints().iterator().next();
        Assert.assertEquals(3, point.getCount());
        Assert.assertEquals(6, point.getSum(), 0.001);
    }

    @Test
    public void testRecordRetryAttemptsMinIsAtLeastOne() {
        meter.recordRetryAttempts("ydb.RunWithRetry", 1);

        HistogramPointData point = findMetric("ydb.client.retry.attempts")
                .getHistogramData().getPoints().iterator().next();
        Assert.assertTrue(point.getMin() >= 1);
    }

    private MetricData findMetric(String name) {
        return reader.collectAllMetrics().stream()
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    private HistogramPointData findHistogramPoint(MetricData metric, String operationName) {
        if (metric == null) {
            return null;
        }
        return metric.getHistogramData().getPoints().stream()
                .filter(p -> operationName.equals(p.getAttributes().get(
                        io.opentelemetry.api.common.AttributeKey.stringKey("operation.name"))))
                .findFirst()
                .orElse(null);
    }
}
