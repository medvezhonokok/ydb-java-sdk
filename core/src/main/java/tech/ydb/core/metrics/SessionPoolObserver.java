package tech.ydb.core.metrics;

public interface SessionPoolObserver {
    int getMinSize();

    int getMaxSize();

    int getIdleCount();

    int getUsedCount();
}
