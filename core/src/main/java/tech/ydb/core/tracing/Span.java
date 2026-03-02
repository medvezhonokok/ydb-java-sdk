package tech.ydb.core.tracing;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import io.grpc.ExperimentalApi;

import tech.ydb.core.Result;
import tech.ydb.core.Status;
import tech.ydb.core.utils.FutureTools;

/**
 * A span represents a timed operation.
 */
@ExperimentalApi("YDB Tracer is experimental and API may change without notice")
public interface Span {
    Span NOOP = new Span() {
        // No operations.
    };

    /**
     * Indicates whether this span carries a real tracing context.
     *
     * @return true for real spans, false for noop span
     */
    default boolean isValid() {
        return false;
    }


    /**
     * Sets span status (success or error) with human-readable message.
     *
     * @param status operation status used to map span attributes
     * @param error operation exception used to map span attributes
     */
    default void setStatus(@Nullable Status status, @Nullable Throwable error) {
    }

    default String getId() {
        return "";
    }

    /**
     * Makes this span current in the active execution context.
     *
     * @return closeable scope handle
     */
    default Scope makeCurrent() {
        return () -> {
        };
    }

    /** Sets a string attribute on the span (ignored by Noop implementation). */
    default void setAttribute(String key, String value) {
    }

    /**
     * Restores context captured when this span was created.
     *
     * <p>The returned scope must be closed, usually via try-with-resources.
     *
     * @return closeable scope handle for restored context
     */
    default Scope restoreContext() {
        return () -> {
        };
    }

    /** Sets a long attribute on the span (ignored by Noop implementation). */
    default void setAttribute(String key, long value) {
    }

    default void setError(Status status) {
    }

    /**
     * Subscribes to a {@link Status} future: when it completes, sets status/error and ends the span.
     * For non-valid spans returns the future as-is without subscribing.
     *
     * @param span the span to finalize
     * @param future the future to observe
     * @return the same future (for chaining)
     * Sets span status to error from exception.
     */
    static CompletableFuture<Status> endOnStatus(Span span, CompletableFuture<Status> future) {
        return span.isValid() ? future.whenComplete((status, th) -> {
            span.setStatus(status, FutureTools.unwrapCompletionException(th));
            span.end();
        }) : future;
    }

    default void setError(Throwable error) {
    }

    /**
     * Subscribes to a {@link Result} future: when it completes, sets status/error and ends the span.
     * For non-valid spans returns the future as-is without subscribing.
     *
     * @param <T> result value type
     * @param span the span to finalize
     * @param future the future to observe
     * @return the same future (for chaining)
     */
    static <T> CompletableFuture<Result<T>> endOnResult(Span span, CompletableFuture<Result<T>> future) {
        return span.isValid() ? future.whenComplete((result, th) -> {
            span.setStatus(result != null ? result.getStatus() : null, FutureTools.unwrapCompletionException(th));
            span.end();
        }) : future;
    }

    default void end() {
    }
}

