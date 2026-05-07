package tech.ydb.core.metrics;

import tech.ydb.core.Status;

public final class NoopMeter implements Meter {
    public static final NoopMeter INSTANCE = new NoopMeter();

    private NoopMeter() {
    }

    public static NoopMeter getInstance() {
        return INSTANCE;
    }

    @Override
    public void recordOperationDuration(String operationName, long durationNanos) {
    }

    @Override
    public void recordOperationFailed(String operationName, Status status) {
    }

    @Override
    public void registerSessionPool(String poolName, SessionPoolObserver observer) {
    }

    @Override
    public void recordSessionCreateTime(String poolName, long durationNanos) {
    }

    @Override
    public void incrementSessionPendingRequests(String poolName) {
    }

    @Override
    public void incrementSessionTimeouts(String poolName) {
    }
}
