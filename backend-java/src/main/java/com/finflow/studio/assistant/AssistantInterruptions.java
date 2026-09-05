package com.finflow.studio.assistant;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

@Component
public class AssistantInterruptions {
    private final ConcurrentHashMap<String, Token> tokens = new ConcurrentHashMap<>();

    public Token token(String scope, String id) {
        var cutoff = Instant.now().minusSeconds(3600);
        tokens.entrySet().removeIf(entry -> entry.getValue().expired(cutoff));
        return tokens.computeIfAbsent(scope + ":" + id, ignored -> new Token());
    }

    public static final class Token {
        private final Instant createdAt = Instant.now();
        private boolean canceled;
        private FutureTask<?> task;
        private Runnable committedAction;

        public synchronized boolean canceled() { return canceled; }
        private synchronized boolean expired(Instant cutoff) { return createdAt.isBefore(cutoff) && task == null; }

        public void cancel() {
            Runnable action;
            synchronized (this) {
                canceled = true;
                if (task != null) task.cancel(true);
                action = committedAction;
            }
            if (action != null) action.run();
        }

        // The handoff must run after commit, before the queued execution is dispatched.
        public void committed(Runnable action) {
            boolean stop;
            synchronized (this) {
                committedAction = action;
                stop = canceled;
            }
            if (stop) action.run();
        }

        public <T> T await(Callable<T> work) {
            var future = new FutureTask<>(work);
            synchronized (this) {
                if (canceled) throw new CancellationException();
                task = future;
                Thread.ofVirtual().name("assistant-decision").start(future);
            }
            try {
                return future.get();
            } catch (InterruptedException exception) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new CancellationException();
            } catch (ExecutionException exception) {
                if (exception.getCause() instanceof RuntimeException cause) throw cause;
                throw new IllegalStateException(exception.getCause());
            } finally {
                synchronized (this) { if (task == future) task = null; }
            }
        }
    }
}
