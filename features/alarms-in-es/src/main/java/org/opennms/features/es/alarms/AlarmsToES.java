/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2018 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2018 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.features.es.alarms;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.opennms.core.time.PseudoClock;
import org.opennms.features.es.alarms.dto.AckStateChangeDTO;
import org.opennms.features.es.alarms.dto.AlarmDocumentDTO;
import org.opennms.features.es.alarms.dto.EventDocumentDTO;
import org.opennms.features.es.alarms.dto.MemoStateChangeDTO;
import org.opennms.features.es.alarms.dto.MemoType;
import org.opennms.features.es.alarms.dto.NodeDocumentDTO;
import org.opennms.features.es.alarms.dto.RelatedAlarmDocumentDTO;
import org.opennms.features.es.alarms.dto.RelatedAlarmStateChangeDTO;
import org.opennms.features.es.alarms.dto.SeverityStateChangeDTO;
import org.opennms.netmgt.dao.api.AlarmEntityListener;
import org.opennms.netmgt.model.OnmsAlarm;
import org.opennms.netmgt.model.OnmsCategory;
import org.opennms.netmgt.model.OnmsEvent;
import org.opennms.netmgt.model.OnmsMemo;
import org.opennms.netmgt.model.OnmsNode;
import org.opennms.netmgt.model.OnmsReductionKeyMemo;
import org.opennms.netmgt.model.OnmsSeverity;
import org.opennms.plugins.elasticsearch.rest.bulk.BulkException;
import org.opennms.plugins.elasticsearch.rest.bulk.BulkRequest;
import org.opennms.plugins.elasticsearch.rest.bulk.BulkWrapper;
import org.opennms.plugins.elasticsearch.rest.bulk.FailedItem;
import org.opennms.plugins.elasticsearch.rest.index.IndexStrategy;
import org.opennms.plugins.elasticsearch.rest.template.TemplateInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import io.searchbox.client.JestClient;
import io.searchbox.core.Bulk;
import io.searchbox.core.Update;

/**
 * TODO: Add periodic synchronization.
 */
public class AlarmsToES implements AlarmEntityListener, Runnable  {

    private static final Logger LOG = LoggerFactory.getLogger(AlarmsToES.class);

    private final JestClient client;
    private final TemplateInitializer templateInitializer;
    private IndexStrategy indexStrategy = IndexStrategy.MONTHLY;
    private int bulkRetryCount = 3;
    private int batchSize = 200;
    private final String indexPrefix = "opennms-alarms";
    private boolean usePseudoClock = false;

    private final BlockingQueue<AlarmDocumentDTO> documentsToIndex = new ArrayBlockingQueue<>(100);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder()
            .setNameFormat("alarm-archiver")
            .build());
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public AlarmsToES(JestClient client, TemplateInitializer templateInitializer) {
        this.client = Objects.requireNonNull(client);
        this.templateInitializer = Objects.requireNonNull(templateInitializer);
    }

    public void init() {
        if (stopped.get()) {
            throw new IllegalStateException("Already destroyed.");
        }
        executor.execute(this);
    }

    public void destroy() {
        stopped.set(true);
        executor.shutdown();
    }

    @Override
    public void run() {
        final List<AlarmDocumentDTO> documents = new ArrayList<>(batchSize);
        while(!stopped.get()) {
            try {
                templateInitializer.initialize();
                documents.clear();
                documents.add(documentsToIndex.take());

                // Nagle like algorithm for filling up the buffer to make efficient use
                // of bulk inserts/updates
                long msToWait = 500;
                while(documents.size() < batchSize && msToWait > 0) {
                    final long timeBeforePoll = System.currentTimeMillis();
                    final AlarmDocumentDTO doc = documentsToIndex.poll(msToWait, TimeUnit.MILLISECONDS);
                    if (doc == null) {
                        // We've waited, and no other single document has shown up
                        break;
                    }
                    documents.add(doc);
                    int remainingCapacity = batchSize - documents.size();
                    if (remainingCapacity > 0) {
                        // Fill up the batch with any remaining items
                        documentsToIndex.drainTo(documents, remainingCapacity);
                    }
                    msToWait -= System.currentTimeMillis() - timeBeforePoll;
                }

                bulkInsert(documents);
            } catch (InterruptedException e) {
                LOG.info("Interrupted. Stopping.");
                return;
            } catch (Exception e) {
                LOG.error("Persisting one or more documents failed.", e);
            }
        }
    }

    public void bulkInsert(List<AlarmDocumentDTO> alarmDocuments) throws PersistenceException, IOException {
        final BulkRequest<AlarmDocumentDTO> bulkRequest = new BulkRequest<>(client, alarmDocuments, (documents) -> {
            final Bulk.Builder bulkBuilder = new Bulk.Builder();
            for (AlarmDocumentDTO alarmDocument : documents) {
                final String index = indexStrategy.getIndex(indexPrefix, Instant.ofEpochMilli(alarmDocument.getLastEventTime()));
                final Update.Builder updateBuilder = new Update.Builder(new UpsertDocument<>(alarmDocument))
                        .index(index)
                        .id(Integer.toString(alarmDocument.getId()))
                        .type(AlarmDocumentDTO.TYPE);
                bulkBuilder.addAction(updateBuilder.build());
            }
            return new BulkWrapper(bulkBuilder);
        }, bulkRetryCount);

        try {
            // the bulk request considers retries
            bulkRequest.execute();
        } catch (BulkException ex) {
            final List<FailedItem<AlarmDocumentDTO>> failedItems;
            if (ex.getBulkResult() != null) {
                failedItems = ex.getBulkResult().getFailedItems();
            } else {
                failedItems = Collections.emptyList();
            }
            throw new PersistenceException(ex.getMessage(), failedItems);
        } catch (IOException ex) {
            LOG.error("An error occurred while executing the given request: {}", ex.getMessage(), ex);
            throw ex;
        }
    }

    private AlarmDocumentDTO toMinimalDocument(OnmsAlarm alarm) {
        final AlarmDocumentDTO document = new AlarmDocumentDTO();
        document.setId(alarm.getId());
        document.setReductionKey(alarm.getReductionKey());
        document.setFirstEventTime(alarm.getFirstEventTime().getTime());
        document.setLastEventTime(alarm.getLastEventTime().getTime());
        document.setUpdateTime(getCurrentTimeMillis());
        return document;
    }

    private AlarmDocumentDTO toDocument(OnmsAlarm alarm) {
        final AlarmDocumentDTO document = toMinimalDocument(alarm);
        document.setAlarmType(alarm.getAlarmType());
        document.setSeverityId(alarm.getSeverityId());
        document.setSeverityLabel(alarm.getSeverityLabel());
        document.setArchived(alarm.isArchived());
        document.setNode(toNode(alarm.getNode()));
        document.setManagedObjectType(alarm.getManagedObjectType());
        document.setManagedObjectInstance(alarm.getManagedObjectInstance());
        // TODO: Set more fields

        // Ack
        document.setAckUser(alarm.getAckUser());
        if (alarm.getAckTime() != null) {
            document.setAckTime(alarm.getAckTime().getTime());
        }

        // Related alarms
        document.setSituation(alarm.isSituation());
        for (OnmsAlarm relatedAlarm : alarm.getRelatedAlarms()) {
            document.addRelatedAlarm(toRelatedAlarm(relatedAlarm));
        }

        return document;
    }

    private NodeDocumentDTO toNode(OnmsNode node) {
        if (node == null) {
            return null;
        }
        // TODO: Cache these
        final NodeDocumentDTO doc = new NodeDocumentDTO();
        doc.setId(node.getId());
        doc.setLabel(node.getLabel());
        doc.setForeignId(node.getForeignId());
        doc.setForeignSource(node.getForeignSource());
        doc.setCategories(node.getCategories().stream()
                .map(OnmsCategory::getName)
                .collect(Collectors.toList()));
        return doc;
    }

    private RelatedAlarmDocumentDTO toRelatedAlarm(OnmsAlarm alarm) {
        final RelatedAlarmDocumentDTO doc = new RelatedAlarmDocumentDTO();
        doc.setId(alarm.getId());
        doc.setReductionKey(alarm.getReductionKey());
        doc.setFirstEventTime(alarm.getFirstEventTime().getTime());
        doc.setLastEventTime(alarm.getLastEventTime().getTime());
        doc.setLastEvent(toEvent(alarm.getLastEvent()));
        doc.setSeverityId(alarm.getSeverityId());
        doc.setSeverityLabel(alarm.getSeverityLabel());
        doc.setAckUser(alarm.getAckUser());
        if (alarm.getAckTime() != null) {
            doc.setAckTime(alarm.getAckTime().getTime());
        }
        doc.setManagedObjectInstance(alarm.getManagedObjectInstance());
        doc.setManagedObjectType(alarm.getManagedObjectType());
        return doc;
    }

    private static EventDocumentDTO toEvent(OnmsEvent event) {
        final EventDocumentDTO doc = new EventDocumentDTO();
        doc.setId(event.getId());
        doc.setUei(event.getEventUei());
        doc.setLogMessage(event.getEventLogMsg());
        doc.setDescription(event.getEventDescr());
        return doc;
    }

    @Override
    public void onAlarmCreated(OnmsAlarm alarm) {
        documentsToIndex.add(toDocument(alarm));
    }

    @Override
    public void onAlarmUpdatedWithReducedEvent(OnmsAlarm alarm, OnmsAlarm alamBeforeReduction) {
        final AlarmDocumentDTO doc = toDocument(alarm);
        appendStateChangesTo(doc, alamBeforeReduction, alarm);
        // TODO: Avoid indexing on every-reduction
        documentsToIndex.add(doc);
    }

    private void appendStateChangesTo(AlarmDocumentDTO doc, OnmsAlarm before, OnmsAlarm after) {
        if (!Objects.equals(before.getSeverity(), after.getSeverity())) {
            appendSeverityStateChange(doc, before.getSeverity());
        }
        if (!Objects.equals(before.getAckUser(), after.getAckUser()) || !Objects.equals(before.getAckTime(), after.getAckTime())) {
            appendAckStateChange(doc, before.getAlarmAckUser(), before.getAlarmAckTime());
        }
        // Append alarm state changes
        appendRelatedAlarmStateChange(doc, before.getRelatedAlarms(), after.getRelatedAlarms());
    }

    private void appendSeverityStateChange(AlarmDocumentDTO doc, OnmsSeverity before) {
        final SeverityStateChangeDTO severityStateChange = new SeverityStateChangeDTO();
        severityStateChange.setTime(doc.getUpdateTime());
        if (before != null) {
            severityStateChange.setSeverityId(before.getId());
            severityStateChange.setSeverityLabel(before.getLabel());
        }
        doc.addSeverityStateChange(severityStateChange);
    }

    private void appendAckStateChange(AlarmDocumentDTO doc, String previousAckUser, Date previousAckTime) {
        final AckStateChangeDTO ackStateChange = new AckStateChangeDTO();
        ackStateChange.setTime(doc.getUpdateTime());
        ackStateChange.setAckUser(previousAckUser);
        if (previousAckTime != null) {
            ackStateChange.setTime(previousAckTime.getTime());
        }
        doc.addAckStateChange(ackStateChange);
    }

    private void appendRelatedAlarmStateChange(AlarmDocumentDTO doc, Set<OnmsAlarm> previousRelatedAlarms, Set<OnmsAlarm> currentRelatedAlarms) {
        // Let's figure out which alarms have changed
        final Map<Integer, OnmsAlarm> previousRelatedAlarmsById = previousRelatedAlarms.stream()
                .collect(Collectors.toMap(OnmsAlarm::getId, a -> a));
        final Map<Integer, OnmsAlarm> currentRelatedAlarmsById = currentRelatedAlarms.stream()
                .collect(Collectors.toMap(OnmsAlarm::getId, a -> a));

        // Handle additions
        final Set<Integer> addedAlarmsIds = Sets.difference(currentRelatedAlarmsById.keySet(), previousRelatedAlarmsById.keySet());
        for (Integer addedAlarmId : addedAlarmsIds) {
            final OnmsAlarm addedAlarm = currentRelatedAlarmsById.get(addedAlarmId);
            final RelatedAlarmStateChangeDTO relatedAlarmStateChange = new RelatedAlarmStateChangeDTO();
            relatedAlarmStateChange.setTime(doc.getUpdateTime());
            relatedAlarmStateChange.setAddition(true);
            relatedAlarmStateChange.setId(addedAlarmId);
            relatedAlarmStateChange.setReductionKey(addedAlarm.getReductionKey());
            doc.addRelatedAlarmStateChange(relatedAlarmStateChange);
        }

        // Handle removals
        final Set<Integer> removedAlarmIds = Sets.difference(previousRelatedAlarmsById.keySet(), currentRelatedAlarmsById.keySet());
        for (Integer removedAlarmId : removedAlarmIds) {
            final OnmsAlarm removedAlarm = previousRelatedAlarmsById.get(removedAlarmId);
            final RelatedAlarmStateChangeDTO relatedAlarmStateChange = new RelatedAlarmStateChangeDTO();
            relatedAlarmStateChange.setTime(doc.getUpdateTime());
            relatedAlarmStateChange.setRemoval(true);
            relatedAlarmStateChange.setId(removedAlarmId);
            relatedAlarmStateChange.setReductionKey(removedAlarm.getReductionKey());
            doc.addRelatedAlarmStateChange(relatedAlarmStateChange);
        }
    }

    private void appendMemoStateChange(MemoType type, AlarmDocumentDTO doc, String previousBody, String previousAuthor, Date previousUpdated) {
        final MemoStateChangeDTO memoStateChange = new MemoStateChangeDTO();
        memoStateChange.setType(type);
        memoStateChange.setTime(doc.getUpdateTime());
        memoStateChange.setBody(previousBody);
        memoStateChange.setAuthor(previousAuthor);
        memoStateChange.setUpdateTime(previousUpdated != null ? previousUpdated.getTime() : null);
        doc.addMemoStateChange(memoStateChange);
    }

    private void appendMemoDeletedStateChange(MemoType type, AlarmDocumentDTO doc, OnmsMemo memo) {
        final MemoStateChangeDTO memoStateChange = new MemoStateChangeDTO();
        memoStateChange.setType(type);
        memoStateChange.setTime(doc.getUpdateTime());
        memoStateChange.setDeleted(true);
        if (memo != null) {
            memoStateChange.setBody(memo.getBody());
            memoStateChange.setAuthor(memo.getAuthor());
            memoStateChange.setUpdateTime(memo.getUpdated() != null ? memo.getUpdated().getTime() : null);
        }
        doc.addMemoStateChange(memoStateChange);
    }

    @Override
    public void onAlarmAcknowledged(OnmsAlarm alarm, String previousAckUser, Date previousAckTime) {
        final AlarmDocumentDTO doc = toMinimalDocument(alarm);
        appendAckStateChange(doc, previousAckUser, previousAckTime);
        documentsToIndex.add(doc);
    }

    @Override
    public void onAlarmUnacknowledged(OnmsAlarm alarm, String previousAckUser, Date previousAckTime) {
        final AlarmDocumentDTO doc = toMinimalDocument(alarm);
        appendAckStateChange(doc, previousAckUser, previousAckTime);
        documentsToIndex.add(doc);
    }

    @Override
    public void onAlarmSeverityUpdated(OnmsAlarm alarm, OnmsSeverity previousSeverity) {
        final AlarmDocumentDTO doc = toMinimalDocument(alarm);
        appendSeverityStateChange(doc, previousSeverity);
        documentsToIndex.add(doc);
    }

    @Override
    public void onAlarmArchived(OnmsAlarm alarm, String previousReductionKey) {
        final AlarmDocumentDTO doc = toMinimalDocument(alarm);
        doc.setArchivedTime(doc.getUpdateTime());
        documentsToIndex.add(doc);
    }

    @Override
    public void onAlarmDeleted(OnmsAlarm alarm) {
        final AlarmDocumentDTO doc = toDocument(alarm);
        doc.setDeletedTime(getCurrentTimeMillis());
        documentsToIndex.add(doc);
    }

    @Override
    public void onStickyMemoUpdated(OnmsAlarm alarm, String previousBody, String previousAuthor, Date previousUpdated) {
        final AlarmDocumentDTO doc = toMinimalDocument(alarm);
        appendMemoStateChange(MemoType.STICKY, doc, previousBody, previousAuthor, previousUpdated);
        documentsToIndex.add(doc);
    }

    @Override
    public void onStickyMemoDeleted(OnmsAlarm alarm, OnmsMemo memo) {
        final AlarmDocumentDTO doc = toMinimalDocument(alarm);
        appendMemoDeletedStateChange(MemoType.JOURNAL, doc, memo);
        documentsToIndex.add(doc);
    }

    @Override
    public void onReductionKeyMemoUpdated(OnmsAlarm alarm, String previousBody, String previousAuthor, Date previousUpdated) {
        final AlarmDocumentDTO doc = toMinimalDocument(alarm);
        appendMemoStateChange(MemoType.JOURNAL, doc, previousBody, previousAuthor, previousUpdated);
        documentsToIndex.add(doc);
    }

    @Override
    public void onReductionKeyMemoDeleted(OnmsAlarm alarm, OnmsReductionKeyMemo memo) {
        final AlarmDocumentDTO doc = toMinimalDocument(alarm);
        appendMemoDeletedStateChange(MemoType.JOURNAL, doc, memo);
        documentsToIndex.add(doc);
    }

    @Override
    public void onLastAutomationTimeUpdated(OnmsAlarm alarm, Date previousLastAutomationTime) {
        // skip these, not worth re-indexing the document for this
    }

    @Override
    public void onRelatedAlarmsUpdated(OnmsAlarm alarm, Set<OnmsAlarm> previousRelatedAlarms) {
        final AlarmDocumentDTO doc = toDocument(alarm);
        appendRelatedAlarmStateChange(doc, previousRelatedAlarms, alarm.getRelatedAlarms());
        documentsToIndex.add(doc);
    }

    private long getCurrentTimeMillis() {
        if (usePseudoClock) {
            return PseudoClock.getInstance().getTime();
        } else {
            return System.currentTimeMillis();
        }
    }

    public void setBulkRetryCount(int bulkRetryCount) {
        this.bulkRetryCount = bulkRetryCount;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public void setIndexStrategy(IndexStrategy indexStrategy) {
        this.indexStrategy = indexStrategy;
    }

    public void setUsePseudoClock(boolean usePseudoClock) {
        this.usePseudoClock = usePseudoClock;
    }
}
