/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.plugins.agentanalytics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.api.core.ApiFutures;
import com.google.auth.Credentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.storage.v1.AppendRowsResponse;
import com.google.cloud.bigquery.storage.v1.BigQueryWriteClient;
import com.google.cloud.bigquery.storage.v1.StreamWriter;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.time.Duration;
import java.util.Optional;
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class BigQueryAgentAnalyticsPluginTest {
  @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

  @Mock private BigQuery mockBigQuery;
  @Mock private StreamWriter mockWriter;
  @Mock private BigQueryWriteClient mockWriteClient;
  @Mock private InvocationContext mockInvocationContext;
  private BaseAgent fakeAgent;

  private BigQueryLoggerConfig config;
  private BigQueryAgentAnalyticsPlugin plugin;

  @Before
  public void setUp() throws Exception {
    fakeAgent = new FakeAgent("agent_name");
    config =
        BigQueryLoggerConfig.builder()
            .setEnabled(true)
            .setDatasetId("dataset")
            .setTableName("table")
            .setBatchSize(10)
            .setBatchFlushInterval(Duration.ofSeconds(10))
            .setAutoSchemaUpgrade(false)
            .setCredentials(mock(Credentials.class))
            .build();

    when(mockBigQuery.getOptions())
        .thenReturn(BigQueryOptions.newBuilder().setProjectId("test-project").build());
    when(mockBigQuery.getTable(any(TableId.class))).thenReturn(mock(Table.class));
    when(mockWriter.append(any(ArrowRecordBatch.class)))
        .thenReturn(ApiFutures.immediateFuture(AppendRowsResponse.getDefaultInstance()));

    plugin =
        new BigQueryAgentAnalyticsPlugin(config, mockBigQuery) {
          @Override
          protected BigQueryWriteClient createWriteClient(BigQueryLoggerConfig config) {
            return mockWriteClient;
          }

          @Override
          protected StreamWriter createWriter(BigQueryLoggerConfig config) {
            return mockWriter;
          }
        };

    Session session = Session.builder("session_id").build();
    when(mockInvocationContext.session()).thenReturn(session);
    when(mockInvocationContext.invocationId()).thenReturn("invocation_id");
    when(mockInvocationContext.agent()).thenReturn(fakeAgent);
    when(mockInvocationContext.userId()).thenReturn("user_id");
  }

  @Test
  public void onUserMessageCallback_appendsToWriter() throws Exception {
    Content content = Content.builder().build();

    plugin.onUserMessageCallback(mockInvocationContext, content).blockingSubscribe();

    plugin.batchProcessor.flush();

    verify(mockWriter, atLeastOnce()).append(any(ArrowRecordBatch.class));
  }

  @Test
  public void beforeRunCallback_appendsToWriter() throws Exception {
    plugin.beforeRunCallback(mockInvocationContext).blockingSubscribe();

    plugin.batchProcessor.flush();

    verify(mockWriter, atLeastOnce()).append(any(ArrowRecordBatch.class));
  }

  @Test
  public void afterRunCallback_flushesAndAppends() throws Exception {
    System.out.println("flushLock1: " + plugin.batchProcessor.flushLock.get());
    plugin.afterRunCallback(mockInvocationContext).blockingSubscribe();

    plugin.batchProcessor.flush();

    verify(mockWriter, atLeastOnce()).append(any(ArrowRecordBatch.class));
  }

  @Test
  public void getStreamName_returnsCorrectFormat() {
    BigQueryLoggerConfig config =
        BigQueryLoggerConfig.builder()
            .setProjectId("test-project")
            .setDatasetId("test-dataset")
            .setTableName("test-table")
            .build();

    String streamName = plugin.getStreamName(config);

    assertEquals(
        "projects/test-project/datasets/test-dataset/tables/test-table/streams/_default",
        streamName);
  }

  @Test
  public void formatContentParts_populatesCorrectFields() {
    Content content = Content.fromParts(Part.fromText("hello"));
    ArrayNode nodes = JsonFormatter.formatContentParts(Optional.of(content), 100);
    assertEquals(1, nodes.size());
    ObjectNode node = (ObjectNode) nodes.get(0);
    assertEquals(0, node.get("part_index").asInt());
    assertEquals("INLINE", node.get("storage_mode").asText());
    assertEquals("hello", node.get("text").asText());
    assertEquals("text/plain", node.get("mime_type").asText());
  }

  @Test
  public void arrowSchema_hasJsonMetadata() {
    Schema schema = BigQuerySchema.getArrowSchema();
    Field contentField = schema.findField("content");
    assertNotNull(contentField);
    assertEquals("google:sqlType:json", contentField.getMetadata().get("ARROW:extension:name"));
  }

  @Test
  public void complexType_appendsToWriter() throws Exception {
    Part part = Part.fromText("test text");
    Content content = Content.fromParts(part);
    plugin.onUserMessageCallback(mockInvocationContext, content).blockingSubscribe();

    plugin.batchProcessor.flush();

    verify(mockWriter, atLeastOnce()).append(any(ArrowRecordBatch.class));
  }

  private static class FakeAgent extends BaseAgent {
    FakeAgent(String name) {
      super(name, "description", null, null, null);
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
      return Flowable.empty();
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
      return Flowable.empty();
    }
  }
}
