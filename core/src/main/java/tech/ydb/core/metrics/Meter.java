package tech.ydb.core.metrics;

import tech.ydb.core.Status;

public interface Meter {
    void recordOperationDuration(String operationName, long durationNanos);

    void recordOperationFailed(String operationName, Status status);

    void registerSessionPool(String poolName, SessionPoolObserver observer);

    void recordSessionCreateTime(String poolName, long durationNanos);

    void incrementSessionPendingRequests(String poolName);

    void incrementSessionTimeouts(String poolName);
}
