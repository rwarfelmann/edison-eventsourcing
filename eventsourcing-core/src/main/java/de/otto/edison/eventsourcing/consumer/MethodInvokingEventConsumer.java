package de.otto.edison.eventsourcing.consumer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.function.Consumer;

public class MethodInvokingEventConsumer<T> implements EventConsumer<T> {

    private final String streamName;
    private final String keyPattern;
    private final Object instance;
    private final Method method;

    public MethodInvokingEventConsumer(final String streamName, final String keyPattern, final Object instance, final Method method) {
        Objects.requireNonNull(streamName, "stream name must not be null");
        Objects.requireNonNull(keyPattern, "key pattern must not be null");
        Objects.requireNonNull(instance, "Unable to build MethodInvokingEventConsumer: instance parameter is null");
        Objects.requireNonNull(method, "Unable to build MethodInvokingEventConsumer: method parameter is null");

        if (method.getParameterCount() != 1) {
            throw new IllegalArgumentException("Unable to build MethodInvokingEventConsumer: illegal number of arguments ");
        }
        final Class<?> paramType = method.getParameterTypes()[0];
        if (!paramType.equals(Event.class)) {
            throw new IllegalArgumentException("Unable to build MethodInvokingEventConsumer: expected parameter type is Event, not " + paramType.getName());
        }

        this.streamName = streamName;
        this.keyPattern = keyPattern;
        this.method = method;
        this.instance = instance;
    }

    @Override
    public String streamName() {
        return streamName;
    }

    @Override
    public String getKeyPattern() {
        return keyPattern;
    }

    @Override
    public Consumer<Event<T>> consumerFunction() {
        return this::accept;
    }

    private void accept(final Event<T> event) {
        try {
            method.invoke(instance, event);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(e);
        }
    }

}
