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

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.core.ApiFutures;
import com.google.cloud.bigquery.storage.v1.AppendRowsResponse;
import com.google.cloud.bigquery.storage.v1.StreamWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.arrow.vector.TimeStampMicroTZVector;
import org.apache.arrow.vector.VectorLoader;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class BatchProcessorTest {
  @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

  @Mock private StreamWriter mockWriter;
  private ScheduledExecutorService executor;
  private BatchProcessor batchProcessor;
  private Schema schema;

  @Before
  public void setUp() {
    executor = Executors.newScheduledThreadPool(1);
    batchProcessor = new BatchProcessor(mockWriter, 10, Duration.ofMinutes(1), 100, executor);
    schema = BigQuerySchema.getArrowSchema();

    when(mockWriter.append(any(ArrowRecordBatch.class)))
        .thenReturn(ApiFutures.immediateFuture(AppendRowsResponse.getDefaultInstance()));
  }

  @After
  public void tearDown() {
    batchProcessor.close();
    executor.shutdown();
  }

  @Test
  public void flush_populatesTimestampFieldCorrectly() throws Exception {
    Instant now = Instant.parse("2026-03-02T19:11:49.631Z");
    Map<String, Object> row = new HashMap<>();
    row.put("timestamp", now);
    row.put("event_type", "TEST_EVENT");

    final boolean[] checksPassed = {false};
    final String[] failureMessage = {null};

    when(mockWriter.append(any(ArrowRecordBatch.class)))
        .thenAnswer(
            invocation -> {
              ArrowRecordBatch recordedBatch = invocation.getArgument(0);
              try (VectorSchemaRoot root =
                  VectorSchemaRoot.create(schema, batchProcessor.allocator)) {
                VectorLoader loader = new VectorLoader(root);
                loader.load(recordedBatch);

                if (root.getRowCount() != 1) {
                  failureMessage[0] = "Expected 1 row, got " + root.getRowCount();
                  return ApiFutures.immediateFuture(AppendRowsResponse.getDefaultInstance());
                }

                var timestampVector = root.getVector("timestamp");
                if (!(timestampVector instanceof TimeStampMicroTZVector tzVector)) {
                  failureMessage[0] = "Vector should be an instance of TimeStampMicroTZVector";
                  return ApiFutures.immediateFuture(AppendRowsResponse.getDefaultInstance());
                }
                if (tzVector.isNull(0)) {
                  failureMessage[0] = "Timestamp should NOT be null";
                } else if (tzVector.get(0) != now.toEpochMilli() * 1000) {
                  failureMessage[0] =
                      "Expected " + (now.toEpochMilli() * 1000) + ", got " + tzVector.get(0);
                } else {
                  checksPassed[0] = true;
                }
              } catch (RuntimeException e) {
                failureMessage[0] = "Exception during check: " + e.getMessage();
              }
              return ApiFutures.immediateFuture(AppendRowsResponse.getDefaultInstance());
            });

    batchProcessor.append(row);
    batchProcessor.flush();

    verify(mockWriter).append(any(ArrowRecordBatch.class));
    assertTrue(failureMessage[0], checksPassed[0]);
  }
}
