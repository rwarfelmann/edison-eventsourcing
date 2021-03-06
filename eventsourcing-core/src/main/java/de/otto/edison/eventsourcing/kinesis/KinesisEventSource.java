package de.otto.edison.eventsourcing.kinesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.otto.edison.eventsourcing.consumer.Event;
import de.otto.edison.eventsourcing.consumer.EventSource;
import de.otto.edison.eventsourcing.consumer.StreamPosition;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import software.amazon.awssdk.services.kinesis.model.Record;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Map;
import java.util.function.*;

import static de.otto.edison.eventsourcing.kinesis.KinesisEvent.kinesisEvent;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Duration.ofMillis;
import static java.util.stream.Collectors.toMap;

public class KinesisEventSource<T> implements EventSource<T> {

    private KinesisStream kinesisStream;
    private Function<String, T> deserializer;

    public KinesisEventSource(final Class<T> payloadType,
                              final ObjectMapper objectMapper,
                              final KinesisStream kinesisStream,
                              final TextEncryptor textEncryptor)
    {
        this.deserializer = in -> {
            try {
                if (payloadType == String.class) {
                    return (T)textEncryptor.decrypt(in);
                } else {
                    return objectMapper.readValue(textEncryptor.decrypt(in), payloadType);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        this.kinesisStream = kinesisStream;
    }

    @Override
    public String getStreamName() {
        return kinesisStream.getStreamName();
    }

    @Override
    public StreamPosition consumeAll(final StreamPosition startFrom,
                                     final Predicate<Event<T>> stopCondition,
                                     final Consumer<Event<T>> consumer) {
        Map<String, String> result = kinesisStream.retrieveAllOpenShards()
                .stream()
                .parallel()
                .map(shard -> shard.consumeRecordsAndReturnLastSeqNumber(
                        startFrom.positionOf(shard.getShardId()),
                        new RecordStopCondition(stopCondition),
                        new RecordConsumer(consumer)))
                .collect(toMap(
                        ShardPosition::getShardId,
                        ShardPosition::getSequenceNumber));
        return StreamPosition.of(result);
    }

    private Event<T> createEvent(Duration durationBehind, Record record) {
        return kinesisEvent(durationBehind, record, byteBuffer -> {
            final String json = UTF_8.decode(record.data()).toString();
            return deserializer.apply(json);
        });
    }

    private class RecordStopCondition implements BiFunction<Long, Record, Boolean> {
        private final Predicate<Event<T>> stopCondition;

        RecordStopCondition(Predicate<Event<T>> stopCondition) {
            this.stopCondition = stopCondition;
        }

        @Override
        public Boolean apply(Long millis, Record record) {
            if (record == null) {
                return stopCondition.test(null);
            }
            return stopCondition.test(createEvent(ofMillis(millis), record));
        }
    }

    private class RecordConsumer implements BiConsumer<Long, Record> {
        private final Consumer<Event<T>> consumer;

        RecordConsumer(Consumer<Event<T>> consumer) {
            this.consumer = consumer;
        }

        @Override
        public void accept(Long millis, Record record) {
            consumer.accept(createEvent(ofMillis(millis), record));
        }
    }
}
