package de.otto.edison.eventsourcing.s3.local;

import org.junit.Before;
import org.junit.Test;
import shaded.org.apache.commons.io.IOUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;

public class LocalS3ClientTest {

    private LocalS3Client testee;

    @Before
    public void setUp() throws Exception {
        testee = new LocalS3Client();
        testee.createBucket(CreateBucketRequest.builder().bucket("someBucket").build());
    }

    @Test
    public void shouldListObjectsInBucket() throws Exception {
        // given
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket("someBucket")
                .key("someObject")
                .build();
        RequestBody requestBody = RequestBody.of("content");
        testee.putObject(putObjectRequest, requestBody);
        // when
        ListObjectsV2Request listObjectsV2Request = ListObjectsV2Request.builder()
                .bucket("someBucket")
                .build();
        ListObjectsV2Response listObjectsV2Response = testee.listObjectsV2(listObjectsV2Request);

        //then
        assertThat(listObjectsV2Response.contents().size(), is(1));
        assertThat(listObjectsV2Response.contents().get(0).key(), is("someObject"));
    }

    @Test
    public void deleteShouldRemoveItemsFromBucket() throws Exception {
        // given
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket("someBucket")
                .key("someObject")
                .build();
        RequestBody requestBody = RequestBody.of("content");
        testee.putObject(putObjectRequest, requestBody);
        testee.deleteObjects(DeleteObjectsRequest.builder().bucket("someBucket").delete(Delete.builder().objects
                (ObjectIdentifier.builder().key("someObject").build()).build()).build());

        // when
        ListObjectsV2Request listObjectsV2Request = ListObjectsV2Request.builder()
                .bucket("someBucket")
                .build();
        ListObjectsV2Response listObjectsV2Response = testee.listObjectsV2(listObjectsV2Request);

        //then
        assertThat(listObjectsV2Response.contents().size(), is(0));
    }

    @Test
    public void listBucketsShouldReturnSingleBucket() throws Exception {
        assertEquals(testee.listBuckets()
                        .buckets()
                        .stream()
                        .map(Bucket::name)
                        .collect(Collectors.toList()),
                Collections.singletonList("someBucket"));
    }

    @Test
    public void listBucketsShouldReturnsSecondBucketSingleBucket() throws Exception {
        // when
        testee.createBucket(CreateBucketRequest.builder().bucket("newBucket").build());

        // then
        assertEquals(testee.listBuckets()
                .buckets()
                .stream()
                .map(Bucket::name)
                .sorted()
                .collect(Collectors.toList()), Arrays.asList("newBucket", "someBucket"));
    }

    @Test
    public void getObjectShouldCreateFileWithData() throws Exception {
        // given
        testee.putObject(PutObjectRequest.builder()
                        .bucket("someBucket")
                        .key("someKey")
                        .build(),
                RequestBody.of("testdata"));
        //when
        Path tempFile = Files.createTempFile("test", "tmp");
        testee.getObject(GetObjectRequest.builder()
                        .bucket("someBucket")
                        .key("someKey")
                        .build(),
                tempFile);

        //then
        List<String> lines = Files.readAllLines(tempFile);
        assertThat(lines.get(0), is("testdata"));
    }

    @Test
    public void getObjectShouldReturnStreamWithData() throws Exception {
        // given
        testee.putObject(PutObjectRequest.builder()
                        .bucket("someBucket")
                        .key("someKey")
                        .build(),
                RequestBody.of("testdata"));
        //when
        ResponseInputStream<GetObjectResponse> inputStream = testee.getObject(GetObjectRequest.builder()
                .bucket("someBucket")
                .key("someKey")
                .build());

        //then
        String data = IOUtils.toString(inputStream, StandardCharsets.UTF_8.name());
        assertThat(data, is("testdata"));
    }

}
