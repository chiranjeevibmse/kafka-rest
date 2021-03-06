/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.kafkarest.resources.v1;

import static io.confluent.kafkarest.TestUtils.assertErrorResponse;
import static io.confluent.kafkarest.TestUtils.assertOKResponse;
import static org.junit.Assert.assertEquals;

import io.confluent.kafkarest.AdminClientWrapper;
import io.confluent.kafkarest.DefaultKafkaRestContext;
import io.confluent.kafkarest.Errors;
import io.confluent.kafkarest.KafkaRestApplication;
import io.confluent.kafkarest.KafkaRestConfig;
import io.confluent.kafkarest.ProducerPool;
import io.confluent.kafkarest.RecordMetadataOrException;
import io.confluent.kafkarest.TestUtils;
import io.confluent.kafkarest.entities.EmbeddedFormat;
import io.confluent.kafkarest.entities.v1.BinaryPartitionProduceRequest;
import io.confluent.kafkarest.entities.v1.BinaryPartitionProduceRequest.BinaryPartitionProduceRecord;
import io.confluent.kafkarest.entities.v1.BinaryTopicProduceRequest;
import io.confluent.kafkarest.entities.v1.BinaryTopicProduceRequest.BinaryTopicProduceRecord;
import io.confluent.kafkarest.entities.v1.PartitionOffset;
import io.confluent.kafkarest.entities.v1.ProduceResponse;
import io.confluent.kafkarest.extension.InstantConverterProvider;
import io.confluent.rest.EmbeddedServerTestHarness;
import io.confluent.rest.RestConfigException;
import io.confluent.rest.exceptions.ConstraintViolationExceptionMapper;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.junit.Before;
import org.junit.Test;

public class PartitionsResourceBinaryProduceTest
    extends EmbeddedServerTestHarness<KafkaRestConfig, KafkaRestApplication> {

  private AdminClientWrapper adminClientWrapper;
  private ProducerPool producerPool;
  private DefaultKafkaRestContext ctx;

  private final String topicName = "topic1";

  private List<BinaryPartitionProduceRecord> produceRecordsOnlyValues;
  private List<BinaryPartitionProduceRecord> produceRecordsWithKeys;
  private List<RecordMetadataOrException> produceResults;
  private final List<PartitionOffset> offsetResults;
  private List<RecordMetadataOrException> produceKafkaAuthorizationExceptionResults;
  private List<PartitionOffset> kafkaAuthorizationExceptionResults;

  public PartitionsResourceBinaryProduceTest() throws RestConfigException {
    adminClientWrapper = EasyMock.createMock(AdminClientWrapper.class);
    producerPool = EasyMock.createMock(ProducerPool.class);
    ctx = new DefaultKafkaRestContext(config,
        producerPool,
        null,
        adminClientWrapper,
        null
    );

    addResource(new TopicsResource(ctx));
    addResource(new PartitionsResource(ctx));
    addResource(InstantConverterProvider.class);

    produceRecordsOnlyValues = Arrays.asList(
        new BinaryPartitionProduceRecord(null, "value"),
        new BinaryPartitionProduceRecord(null, "value2")
    );
    produceRecordsWithKeys = Arrays.asList(
        new BinaryPartitionProduceRecord("key", "value"),
        new BinaryPartitionProduceRecord("key2", "value2")
    );
    TopicPartition tp0 = new TopicPartition(topicName, 0);
    produceResults = Arrays.asList(
        new RecordMetadataOrException(new RecordMetadata(tp0, 0L, 0L, 0L, 0L, 1, 1), null),
        new RecordMetadataOrException(new RecordMetadata(tp0, 0L, 1L, 0L, 0L, 1, 1), null)
    );
    offsetResults = Arrays.asList(
        new PartitionOffset(0, 0L, null, null),
        new PartitionOffset(0, 1L, null, null)
    );

    produceKafkaAuthorizationExceptionResults = Collections.singletonList(
        new RecordMetadataOrException(null, new TopicAuthorizationException(topicName)));
    kafkaAuthorizationExceptionResults = Collections.singletonList(
        new PartitionOffset(null, null, Errors.KAFKA_AUTHORIZATION_ERROR_CODE,
            new TopicAuthorizationException(topicName).getMessage()));
  }

  @Before
  @Override
  public void setUp() throws Exception {
    super.setUp();
    EasyMock.reset(adminClientWrapper, producerPool);
  }

  private <K, V> Response produceToPartition(String topic, int partition, String acceptHeader,
      String requestMediatype,
      EmbeddedFormat recordFormat,
      List<BinaryPartitionProduceRecord> records,
      final List<RecordMetadataOrException> results) throws Exception {
    BinaryPartitionProduceRequest request = BinaryPartitionProduceRequest.create(records);
    final Capture<ProducerPool.ProduceRequestCallback>
        produceCallback =
        Capture.newInstance();
    EasyMock.expect(adminClientWrapper.topicExists(topic)).andReturn(true);
    EasyMock.expect(adminClientWrapper.partitionExists(topic, partition)).andReturn(true);
    producerPool.produce(EasyMock.eq(topic),
        EasyMock.eq(partition),
        EasyMock.eq(recordFormat),
        EasyMock.anyObject(),
        EasyMock.capture(produceCallback));
    EasyMock.expectLastCall().andAnswer(new IAnswer<Object>() {
      @Override
      public Object answer() throws Throwable {
        if (results == null) {
          throw new Exception();
        } else {
          produceCallback.getValue().onCompletion((Integer) null, (Integer) null, results);
        }
        return null;
      }
    });
    EasyMock.replay(adminClientWrapper, producerPool);

    Response
        response =
        request("/topics/" + topic + "/partitions/" + ((Integer) partition).toString(),
                acceptHeader)
            .post(Entity.entity(request, requestMediatype));

    EasyMock.verify(producerPool);

    return response;
  }

  @Test
  public void testProduceToPartitionOnlyValues() throws Exception {
    for (TestUtils.RequestMediaType mediatype : TestUtils.V1_ACCEPT_MEDIATYPES) {
      for (String requestMediatype : TestUtils.V1_REQUEST_ENTITY_TYPES_BINARY) {
        Response
            rawResponse =
            produceToPartition(topicName, 0, mediatype.header, requestMediatype,
                               EmbeddedFormat.BINARY,
                               produceRecordsOnlyValues, produceResults);
        assertOKResponse(rawResponse, mediatype.expected);
        ProduceResponse response = TestUtils.tryReadEntityOrLog(rawResponse, ProduceResponse.class);

        assertEquals(offsetResults, response.getOffsets());
        assertEquals(null, response.getKeySchemaId());
        assertEquals(null, response.getValueSchemaId());

        EasyMock.reset(adminClientWrapper, producerPool);
      }
    }
  }

  @Test
  public void testProduceToPartitionByKey() throws Exception {
    for (TestUtils.RequestMediaType mediatype : TestUtils.V1_ACCEPT_MEDIATYPES) {
      for (String requestMediatype : TestUtils.V1_REQUEST_ENTITY_TYPES_BINARY) {
        Response
            rawResponse =
            produceToPartition(topicName, 0, mediatype.header, requestMediatype,
                EmbeddedFormat.BINARY,
                               produceRecordsWithKeys, produceResults);
        assertOKResponse(rawResponse, mediatype.expected);
        ProduceResponse response = TestUtils.tryReadEntityOrLog(rawResponse, ProduceResponse.class);

        assertEquals(offsetResults, response.getOffsets());
        assertEquals(null, response.getKeySchemaId());
        assertEquals(null, response.getValueSchemaId());

        EasyMock.reset(adminClientWrapper, producerPool);
      }
    }
  }

  @Test
  public void testProduceToPartitionFailure() throws Exception {
    for (TestUtils.RequestMediaType mediatype : TestUtils.V1_ACCEPT_MEDIATYPES) {
      for (String requestMediatype : TestUtils.V1_REQUEST_ENTITY_TYPES_BINARY) {
        // null offsets triggers a generic exception
        Response
            rawResponse =
            produceToPartition(topicName, 0, mediatype.header, requestMediatype,
                               EmbeddedFormat.BINARY,
                               produceRecordsWithKeys, null);
        assertErrorResponse(
            Response.Status.INTERNAL_SERVER_ERROR, rawResponse,
            mediatype.expected
        );

        EasyMock.reset(adminClientWrapper, producerPool);
      }
    }
  }

  @Test
  public void testProduceInvalidRequest() throws Exception {
    for (TestUtils.RequestMediaType mediatype : TestUtils.V1_ACCEPT_MEDIATYPES) {
      for (String requestMediatype : TestUtils.V1_REQUEST_ENTITY_TYPES_BINARY) {
        EasyMock.expect(adminClientWrapper.topicExists(topicName)).andReturn(true);
        EasyMock.replay(adminClientWrapper);
        Response response = request("/topics/" + topicName + "/partitions/0", mediatype.header)
            .post(Entity.entity("{}", requestMediatype));
        assertErrorResponse(ConstraintViolationExceptionMapper.UNPROCESSABLE_ENTITY,
            response,
            ConstraintViolationExceptionMapper.UNPROCESSABLE_ENTITY_CODE,
            null,
            mediatype.expected);
        EasyMock.verify();

        // Invalid base64 encoding
        response = request("/topics/" + topicName + "/partitions/0", mediatype.header)
            .post(Entity.entity("{\"records\":[{\"value\":\"aGVsbG8==\"}]}", requestMediatype));
        assertErrorResponse(ConstraintViolationExceptionMapper.UNPROCESSABLE_ENTITY,
            response,
            ConstraintViolationExceptionMapper.UNPROCESSABLE_ENTITY_CODE,
            null,
            mediatype.expected);
        EasyMock.verify();

        // Invalid data -- include partition in request
        BinaryTopicProduceRequest topicRequest =
            BinaryTopicProduceRequest.create(
                Collections.singletonList(new BinaryTopicProduceRecord("key", "value", 0)));
        response = request("/topics/" + topicName + "/partitions/0", mediatype.header)
            .post(Entity.entity(topicRequest, requestMediatype));
        assertErrorResponse(ConstraintViolationExceptionMapper.UNPROCESSABLE_ENTITY,
            response,
            ConstraintViolationExceptionMapper.UNPROCESSABLE_ENTITY_CODE,
            null,
            mediatype.expected);
        EasyMock.verify();

        EasyMock.reset(adminClientWrapper, producerPool);
      }
    }
  }

  @Test
  public void testProduceToPartitionAuthorizationErrors() throws Exception {
    for (TestUtils.RequestMediaType mediatype : TestUtils.V1_ACCEPT_MEDIATYPES) {
      for (String requestMediatype : TestUtils.V1_REQUEST_ENTITY_TYPES_BINARY) {
        Response
            rawResponse =
            produceToPartition(topicName, 0, mediatype.header, requestMediatype,
                EmbeddedFormat.BINARY,
                produceRecordsOnlyValues, produceKafkaAuthorizationExceptionResults);
        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), rawResponse.getStatus());
        ProduceResponse response = TestUtils.tryReadEntityOrLog(rawResponse, ProduceResponse.class);

        assertEquals(kafkaAuthorizationExceptionResults, response.getOffsets());
        assertEquals(null, response.getKeySchemaId());
        assertEquals(null, response.getValueSchemaId());

        EasyMock.reset(adminClientWrapper, producerPool);
      }
    }
  }
}
