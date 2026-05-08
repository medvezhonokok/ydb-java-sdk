package tech.ydb.query.tools;

import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import tech.ydb.auth.TokenAuthProvider;
import tech.ydb.common.transaction.TxMode;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.core.metrics.OpenTelemetryMeter;
import tech.ydb.query.QueryClient;
import tech.ydb.test.junit4.YdbHelperRule;


public class SessionRetryContextMetricsTest {
    @ClassRule
    public static final YdbHelperRule YDB = new YdbHelperRule();

    private static InMemoryMetricReader metricReader;
    private static SdkMeterProvider meterProvider;
    private static OpenTelemetryMeter ydbMeter;
    private static GrpcTransport transport;

    private SessionRetryContext retryCtx;

    private QueryClient queryClient;

    @BeforeClass
    public static void initTransport() {
        metricReader = InMemoryMetricReader.create();
        meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(metricReader)
                .build();

        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setMeterProvider(meterProvider)
                .build();

        ydbMeter = OpenTelemetryMeter.fromOpenTelemetry(openTelemetry, YDB.database(), YDB.endpoint());

        transport = GrpcTransport.forEndpoint(YDB.endpoint(), YDB.database())
                .withAuthProvider(new TokenAuthProvider(YDB.authToken()))
                .build();
    }

    @AfterClass
    public static void closeTransport() throws IOException {
        transport.close();
        meterProvider.close();
        metricReader.close();
    }

    @Before
    public void init() {
        queryClient = QueryClient.newClient(transport).withMeter(ydbMeter).build();
        retryCtx = SessionRetryContext.create(queryClient).withMeter(ydbMeter).build();
    }

    @After
    public void closeClient() {
        retryCtx.supplyResult(session -> session.createQuery("DROP TABLE episodes;", TxMode.NONE).execute())
                .join();
        retryCtx.supplyResult(session -> session.createQuery("DROP TABLE seasons;", TxMode.NONE).execute())
                .join();
        retryCtx.supplyResult(session -> session.createQuery("DROP TABLE series;", TxMode.NONE).execute())
                .join();

        queryClient.close();
    }

    @Test
    public void retryContextMetrics() {
        retryCtx.supplyResult(session -> session.createQuery(""
             + "CREATE TABLE series ("
             + "  series_id UInt64,"
             + "  title Text,"
             + "  series_info Text,"
             + "  release_date Date,"
             + "  PRIMARY KEY(series_id)"
             + ")", TxMode.NONE).execute()
        ).join().getStatus().expectSuccess("Can't create table series");

        retryCtx.supplyResult(session -> session.createQuery(""
             + "CREATE TABLE seasons ("
             + "  series_id UInt64,"
             + "  season_id UInt64,"
             + "  title Text,"
             + "  first_aired Date,"
             + "  last_aired Date,"
             + "  PRIMARY KEY(series_id, season_id)"
             + ")", TxMode.NONE).execute()
        ).join().getStatus().expectSuccess("Can't create table seasons");

        retryCtx.supplyResult(session -> session.createQuery(""
             + "CREATE TABLE episodes ("
             + "  series_id UInt64,"
             + "  season_id UInt64,"
             + "  episode_id UInt64,"
             + "  title Text,"
             + "  air_date Date,"
             + "  PRIMARY KEY(series_id, season_id, episode_id)"
             + ")", TxMode.NONE).execute()
        ).join().getStatus().expectSuccess("Can't create table episodes");

        Collection<MetricData> metrics = metricReader.collectAllMetrics();
        Set<String> names = metrics.stream().map(MetricData::getName).collect(Collectors.toSet());
        org.junit.Assert.assertTrue(names.contains("ydb.client.retry.duration"));
        org.junit.Assert.assertTrue(names.contains("ydb.client.retry.attempts"));
    }
}
