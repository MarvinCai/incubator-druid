/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.indexing.seekablestream;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import org.apache.druid.data.input.Committer;
import org.apache.druid.indexing.common.TaskToolbox;
import org.apache.druid.indexing.common.actions.SegmentTransactionalInsertAction;
import org.apache.druid.indexing.seekablestream.common.OrderedPartitionableRecord;
import org.apache.druid.indexing.seekablestream.common.OrderedSequenceNumber;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.segment.realtime.appenderator.TransactionalSegmentPublisher;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

public class SequenceMetadata<PartitionIdType, SequenceOffsetType>
{
  private final int sequenceId;
  private final String sequenceName;
  private final Set<PartitionIdType> exclusiveStartPartitions;
  private final Set<PartitionIdType> assignments;
  private final boolean sentinel;
  private boolean checkpointed;
  /**
   * Lock for accessing {@link #endOffsets} and {@link #checkpointed}. This lock is required because
   * {@link #setEndOffsets)} can be called by both the main thread and the HTTP thread.
   */
  private final ReentrantLock lock = new ReentrantLock();

  final Map<PartitionIdType, SequenceOffsetType> startOffsets;
  final Map<PartitionIdType, SequenceOffsetType> endOffsets;

  @JsonCreator
  public SequenceMetadata(
      @JsonProperty("sequenceId") int sequenceId,
      @JsonProperty("sequenceName") String sequenceName,
      @JsonProperty("startOffsets") Map<PartitionIdType, SequenceOffsetType> startOffsets,
      @JsonProperty("endOffsets") Map<PartitionIdType, SequenceOffsetType> endOffsets,
      @JsonProperty("checkpointed") boolean checkpointed,
      @JsonProperty("exclusiveStartPartitions") Set<PartitionIdType> exclusiveStartPartitions
  )
  {
    Preconditions.checkNotNull(sequenceName);
    Preconditions.checkNotNull(startOffsets);
    Preconditions.checkNotNull(endOffsets);
    this.sequenceId = sequenceId;
    this.sequenceName = sequenceName;
    this.startOffsets = ImmutableMap.copyOf(startOffsets);
    this.endOffsets = new HashMap<>(endOffsets);
    this.assignments = new HashSet<>(startOffsets.keySet());
    this.checkpointed = checkpointed;
    this.sentinel = false;
    this.exclusiveStartPartitions = exclusiveStartPartitions == null
                                    ? Collections.emptySet()
                                    : exclusiveStartPartitions;
  }

  @JsonProperty
  public Set<PartitionIdType> getExclusiveStartPartitions()
  {
    return exclusiveStartPartitions;
  }

  @JsonProperty
  public int getSequenceId()
  {
    return sequenceId;
  }

  @JsonProperty
  public boolean isCheckpointed()
  {
    lock.lock();
    try {
      return checkpointed;
    }
    finally {
      lock.unlock();
    }
  }

  @JsonProperty
  public String getSequenceName()
  {
    return sequenceName;
  }

  @JsonProperty
  public Map<PartitionIdType, SequenceOffsetType> getStartOffsets()
  {
    return startOffsets;
  }

  @JsonProperty
  public Map<PartitionIdType, SequenceOffsetType> getEndOffsets()
  {
    lock.lock();
    try {
      return endOffsets;
    }
    finally {
      lock.unlock();
    }
  }

  @JsonProperty
  public boolean isSentinel()
  {
    return sentinel;
  }

  void setEndOffsets(Map<PartitionIdType, SequenceOffsetType> newEndOffsets)
  {
    lock.lock();
    try {
      endOffsets.putAll(newEndOffsets);
      checkpointed = true;
    }
    finally {
      lock.unlock();
    }
  }

  void updateAssignments(
      SeekableStreamIndexTaskRunner<PartitionIdType, SequenceOffsetType> runner,
      Map<PartitionIdType, SequenceOffsetType> nextPartitionOffset
  )
  {
    lock.lock();
    try {
      assignments.clear();
      nextPartitionOffset.forEach((key, value) -> {
        SequenceOffsetType endOffset = endOffsets.get(key);
        if (SeekableStreamPartitions.NO_END_SEQUENCE_NUMBER.equals(endOffset)
            || runner.createSequenceNumber(endOffset).compareTo(runner.createSequenceNumber(nextPartitionOffset.get(key))) > 0) {
          assignments.add(key);
        }
      });
    }
    finally {
      lock.unlock();
    }
  }

  boolean isOpen()
  {
    return !assignments.isEmpty();
  }

  boolean canHandle(
      SeekableStreamIndexTaskRunner<PartitionIdType, SequenceOffsetType> runner,
      OrderedPartitionableRecord<PartitionIdType, SequenceOffsetType> record
  )
  {
    lock.lock();
    try {
      final OrderedSequenceNumber<SequenceOffsetType> partitionEndOffset = runner.createSequenceNumber(endOffsets.get(record.getPartitionId()));
      final OrderedSequenceNumber<SequenceOffsetType> partitionStartOffset = runner.createSequenceNumber(startOffsets.get(
          record.getPartitionId()));
      final OrderedSequenceNumber<SequenceOffsetType> recordOffset = runner.createSequenceNumber(record.getSequenceNumber());
      if (!isOpen() || recordOffset == null || partitionEndOffset == null || partitionStartOffset == null) {
        return false;
      }
      boolean ret;
      if (runner.isStartingSequenceOffsetsExclusive()) {
        ret = recordOffset.compareTo(partitionStartOffset)
              >= (getExclusiveStartPartitions().contains(record.getPartitionId()) ? 1 : 0);
      } else {
        ret = recordOffset.compareTo(partitionStartOffset) >= 0;
      }

      if (runner.isEndSequenceOffsetsExclusive()) {
        ret &= recordOffset.compareTo(partitionEndOffset) < 0;
      } else {
        ret &= recordOffset.compareTo(partitionEndOffset) <= 0;
      }

      return ret;
    }
    finally {
      lock.unlock();
    }
  }

  @Override
  public String toString()
  {
    lock.lock();
    try {
      return "SequenceMetadata{" +
             "sequenceName='" + sequenceName + '\'' +
             ", sequenceId=" + sequenceId +
             ", startOffsets=" + startOffsets +
             ", endOffsets=" + endOffsets +
             ", assignments=" + assignments +
             ", sentinel=" + sentinel +
             ", checkpointed=" + checkpointed +
             '}';
    }
    finally {
      lock.unlock();
    }
  }

  Supplier<Committer> getCommitterSupplier(
      SeekableStreamIndexTaskRunner<PartitionIdType, SequenceOffsetType> runner,
      String stream,
      Map<PartitionIdType, SequenceOffsetType> lastPersistedOffsets
  )
  {
    // Set up committer.
    return () ->
        new Committer()
        {
          @Override
          public Object getMetadata()
          {
            lock.lock();

            try {
              Preconditions.checkState(
                  assignments.isEmpty(),
                  "This committer can be used only once all the records till sequences [%s] have been consumed, also make"
                  + " sure to call updateAssignments before using this committer",
                  endOffsets
              );


              // merge endOffsets for this sequence with globally lastPersistedOffsets
              // This is done because this committer would be persisting only sub set of segments
              // corresponding to the current sequence. Generally, lastPersistedOffsets should already
              // cover endOffsets but just to be sure take max of sequences and persist that
              for (Map.Entry<PartitionIdType, SequenceOffsetType> partitionOffset : endOffsets.entrySet()) {
                SequenceOffsetType newOffsets = partitionOffset.getValue();
                if (lastPersistedOffsets.containsKey(partitionOffset.getKey())
                    && runner.createSequenceNumber(lastPersistedOffsets.get(partitionOffset.getKey()))
                             .compareTo(runner.createSequenceNumber(newOffsets)) > 0) {
                  newOffsets = lastPersistedOffsets.get(partitionOffset.getKey());
                }
                lastPersistedOffsets.put(
                    partitionOffset.getKey(),
                    newOffsets
                );
              }

              // Publish metadata can be different from persist metadata as we are going to publish only
              // subset of segments
              return ImmutableMap.of(
                  SeekableStreamIndexTaskRunner.METADATA_NEXT_PARTITIONS,
                  new SeekableStreamPartitions<>(stream, lastPersistedOffsets),
                  SeekableStreamIndexTaskRunner.METADATA_PUBLISH_PARTITIONS,
                  new SeekableStreamPartitions<>(stream, endOffsets)
              );
            }
            finally {
              lock.unlock();
            }
          }

          @Override
          public void run()
          {
            // Do nothing.
          }
        };

  }

  TransactionalSegmentPublisher createPublisher(
      SeekableStreamIndexTaskRunner<PartitionIdType, SequenceOffsetType> runner,
      TaskToolbox toolbox,
      boolean useTransaction
  )
  {
    return (segments, commitMetadata) -> {
      final Map commitMetaMap = (Map) Preconditions.checkNotNull(commitMetadata, "commitMetadata");
      final SeekableStreamPartitions<PartitionIdType, SequenceOffsetType> finalPartitions =
          runner.deserializePartitionsFromMetadata(
              toolbox.getObjectMapper(),
              commitMetaMap.get(SeekableStreamIndexTaskRunner.METADATA_PUBLISH_PARTITIONS)
          );

      // Sanity check, we should only be publishing things that match our desired end state.
      if (!getEndOffsets().equals(finalPartitions.getPartitionSequenceNumberMap())) {
        throw new ISE(
            "WTF?! Driver for sequence [%s], attempted to publish invalid metadata[%s].",
            toString(),
            commitMetadata
        );
      }

      final SegmentTransactionalInsertAction action;

      if (useTransaction) {
        action = new SegmentTransactionalInsertAction(
            segments,
            runner.createDataSourceMetadata(
                new SeekableStreamPartitions<>(finalPartitions.getStream(), getStartOffsets())
            ),
            runner.createDataSourceMetadata(finalPartitions)
        );
      } else {
        action = new SegmentTransactionalInsertAction(segments, null, null);
      }

      return toolbox.getTaskActionClient().submit(action);
    };
  }
}
