package de.otto.edison.eventsourcing.consumer;

import org.junit.Test;
import software.amazon.awssdk.services.kinesis.model.Record;

import java.nio.ByteBuffer;
import java.time.Instant;

import static de.otto.edison.eventsourcing.kinesis.KinesisEvent.kinesisEvent;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class KinesisEventTest {

    @Test
    public void shouldBuildKinesisEvent() {
        final Instant now = Instant.now();
        final Record record = Record.builder()
                .partitionKey("42")
                .data(ByteBuffer.wrap("ßome dätä".getBytes(UTF_8)))
                .approximateArrivalTimestamp(now)
                .sequenceNumber("00001")
                .build();
        final Event<String> event = kinesisEvent(
                record,
                (bb) -> UTF_8.decode(bb).toString());
        assertThat(event.key(), is("42"));
        assertThat(event.payload(), is("ßome dätä"));
        assertThat(event.arrivalTimestamp(), is(now));
        assertThat(event.sequenceNumber(), is("00001"));
    }
}
