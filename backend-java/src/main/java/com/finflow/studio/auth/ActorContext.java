package com.finflow.studio.auth;

import java.util.Objects;

public final class ActorContext {
    private static final String DEFAULT_ACTOR = "default";
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private ActorContext() { }

    public static String current() {
        return Objects.requireNonNullElse(CURRENT.get(), DEFAULT_ACTOR);
    }

    public static Scope bind(String actorId) {
        var previous = CURRENT.get();
        CURRENT.set(actorId);
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    public static void runAs(String actorId, Runnable action) {
        try (var ignored = bind(actorId)) {
            action.run();
        }
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
