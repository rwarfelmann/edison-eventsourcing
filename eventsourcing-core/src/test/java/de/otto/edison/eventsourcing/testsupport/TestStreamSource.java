package de.otto.edison.eventsourcing.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import de.otto.edison.eventsourcing.consumer.StreamPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.AmazonServiceException;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.google.common.collect.ImmutableList.toImmutableList;

/**
 * This class is a data source for supplying input to the Amazon Kinesis stream. It reads lines from the
 * input file specified in the constructor and emits them by calling String.getBytes() into the
 * stream defined in the KinesisConnectorConfiguration.
 */
public class TestStreamSource {

    private static final Logger LOG = LoggerFactory.getLogger(TestStreamSource.class);

    private final String streamName;
    private final KinesisClient kinesisClient;
    private final String inputFile;
    private final ObjectMapper objectMapper;

    private final Map<String, String> mapShardIdToFirstWrittenSequence = new HashMap<>();
    private final Map<String, String> mapShardIdToLastWrittenSequence = new HashMap<>();

    /**
     * Creates a new TestStreamSource.
     *
     * @param inputFile File containing record data to emit on each line
     */
    public TestStreamSource(KinesisClient kinesisClient, String streamName, String inputFile) {
        this.kinesisClient = kinesisClient;
        this.inputFile = inputFile;
        this.objectMapper = new ObjectMapper();
        this.streamName = streamName;
    }

    public void writeToStream() {
        mapShardIdToFirstWrittenSequence.clear();
        mapShardIdToLastWrittenSequence.clear();

        InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(inputFile);
        if (inputStream == null) {
            throw new IllegalStateException("Could not find input file: " + inputFile);
        }
        try {
            putRecords(streamName, createFakeRecords());
            processInputStream(streamName, inputStream);
        } catch (IOException e) {
            LOG.error("Encountered exception while putting data in source stream.", e);
        }
    }

    public StreamPosition getLastStreamPosition() {
        return StreamPosition.of(mapShardIdToLastWrittenSequence);
    }

    public StreamPosition getFirstReadPosition() {
        return StreamPosition.of(mapShardIdToFirstWrittenSequence);
    }

    /**
     * Process the input file and send PutRecordRequests to Amazon Kinesis.
     * <p>
     * This function serves to Isolate TestStreamSource logic so subclasses
     * can process input files differently.
     *
     * @param inputStream the input stream to process
     * @throws IOException throw exception if error processing inputStream.
     */
    protected void processInputStream(String streamName, InputStream inputStream) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            int lines = 0;
            List<PutRecordsRequestEntry> records = new ArrayList<>(100);

            while ((line = br.readLine()) != null) {
                KinesisMessageModel kinesisMessageModel = objectMapper.readValue(line, KinesisMessageModel.class);
                records.add(PutRecordsRequestEntry.builder()
                        .data(ByteBuffer.wrap(line.getBytes()))
                        .partitionKey(Integer.toString(kinesisMessageModel.getUserid()))
                        .build());
                lines++;
                if (lines % 100 == 0) {
                    putRecords(streamName, records);
                    LOG.info("writing data; lines: {}", lines);
                    records.clear();
                }
            }

            if (!records.isEmpty()) {
                putRecords(streamName, records);
            }
            LOG.info("Added {} records to stream source.", lines);
        }
    }

    private List<PutRecordsRequestEntry> createFakeRecords() {
        Map<String, String> hashKeysForShards = getStartHashKeysForShards(streamName);
        return hashKeysForShards.entrySet().stream()
                .map(entry -> PutRecordsRequestEntry.builder()
                        .data(ByteBuffer.wrap("{}".getBytes(Charsets.UTF_8)))
                        .partitionKey(entry.getValue())
                        .explicitHashKey(entry.getValue())
                        .build())
                .collect(toImmutableList());

    }

    public void putRecords(String streamName, List<PutRecordsRequestEntry> records) {
        if (records.isEmpty()) {
            throw new IllegalArgumentException("records must not be empty");
        }

        PutRecordsRequest putRecordsRequest = PutRecordsRequest.builder()
                .streamName(streamName)
                .records(records)
                .build();
        List<PutRecordsResultEntry> writtenRecords = kinesisClient.putRecords(putRecordsRequest).records();
        collectFirstAndLastSequenceNumber(writtenRecords);
    }

    private void collectFirstAndLastSequenceNumber(List<PutRecordsResultEntry> writtenRecords) {
        writtenRecords.forEach(entry -> {
            mapShardIdToFirstWrittenSequence.putIfAbsent(entry.shardId(), entry.sequenceNumber());
            mapShardIdToLastWrittenSequence.put(entry.shardId(), entry.sequenceNumber());
        });
    }

    private Map<String, String> getStartHashKeysForShards(String streamName) {
        DescribeStreamRequest describeStreamRequest = DescribeStreamRequest.builder()
                .streamName(streamName)
                .build();
        try {
            return kinesisClient.describeStream(describeStreamRequest).streamDescription()
                    .shards()
                    .stream()
                    .filter(this::isShardOpen)
                    .collect(Collectors.toMap(Shard::shardId, shard -> shard.hashKeyRange().startingHashKey()));
        } catch (AmazonServiceException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isShardOpen(Shard shard) {
        return shard.sequenceNumberRange().endingSequenceNumber() == null;
    }
}
