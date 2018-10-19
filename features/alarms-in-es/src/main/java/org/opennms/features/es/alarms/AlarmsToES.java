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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.opennms.features.es.alarms.dto.AckStateChangeDTO;
import org.opennms.features.es.alarms.dto.AlarmDocumentDTO;
import org.opennms.netmgt.dao.api.AlarmEntityListener;
import org.opennms.netmgt.model.OnmsAlarm;
import org.opennms.netmgt.model.OnmsMemo;
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

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import io.searchbox.client.JestClient;
import io.searchbox.core.Bulk;
import io.searchbox.core.Update;

public class AlarmsToES implements AlarmEntityListener, Runnable  {

    private static final Logger LOG = LoggerFactory.getLogger(AlarmsToES.class);

    private final JestClient client;
    private final TemplateInitializer templateInitializer;
    private IndexStrategy indexStrategy = IndexStrategy.MONTHLY;
    private int bulkRetryCount = 3;
    private int batchSize = 200;
    private final String indexPrefix = "opennms-alarms";

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

                long msToWait = 500;
                while(documents.size() < batchSize && msToWait > 0) {
                    long timeBeforePoll = System.currentTimeMillis();
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

    private AlarmDocumentDTO toDocument(OnmsAlarm alarm) {
        final AlarmDocumentDTO document = new AlarmDocumentDTO();
        document.setId(alarm.getId());
        document.setReductionKey(alarm.getReductionKey());
        document.setFirstEventTime(alarm.getFirstEventTime().getTime());
        document.setLastEventTime(alarm.getLastEventTime().getTime());
        document.setUpdateTime(System.currentTimeMillis());

        /*
        // TODO: Add other node details - use node cache?
        if (alarm.getNodeId() != null) {
            document.setNodeId(alarm.getNodeId());
        }
        document.set
        */
        return document;
    }

    @Override
    public void onAlarmCreated(OnmsAlarm alarm) {
        documentsToIndex.add(toDocument(alarm));
    }

    @Override
    public void onAlarmUpdatedWithReducedEvent(OnmsAlarm alarm) {
        documentsToIndex.add(toDocument(alarm));
        // TODO: Handle fields that may of changed
    }

    @Override
    public void onAlarmAcknowledged(OnmsAlarm alarm, String previousAckUser, Date previousAckTime) {
        final AlarmDocumentDTO doc = toDocument(alarm);

        final AckStateChangeDTO ackStateChange = new AckStateChangeDTO();
        ackStateChange.setTime(doc.getUpdateTime());
        ackStateChange.setAckUser(previousAckUser);
        if (previousAckTime != null) {
            ackStateChange.setTime(previousAckTime.getTime());
        }
        doc.addAckStateChange(ackStateChange);

        documentsToIndex.add(doc);
    }

    @Override
    public void onAlarmUnacknowledged(OnmsAlarm alarm, String previousAckUser, Date previousAckTime) {

    }

    @Override
    public void onAlarmSeverityUpdated(OnmsAlarm alarm, OnmsSeverity previousSeverity) {

    }

    @Override
    public void onAlarmArchived(OnmsAlarm alarm, String previousReductionKey) {

    }

    @Override
    public void onAlarmDeleted(OnmsAlarm alarm) {

    }

    @Override
    public void onStickyMemoUpdated(OnmsAlarm alarm, String previousBody, String previousAuthor, Date previousUpdated) {

    }

    @Override
    public void onReductionKeyMemoUpdated(OnmsAlarm alarm, String previousBody, String previousAuthor, Date previousUpdated) {

    }

    @Override
    public void onStickyMemoDeleted(OnmsAlarm alarm, OnmsMemo memo) {

    }

    @Override
    public void onReductionKeyMemoDeleted(OnmsAlarm alarm, OnmsReductionKeyMemo memo) {

    }

    @Override
    public void onLastAutomationTimeUpdated(OnmsAlarm alarm, Date previousLastAutomationTime) {

    }

    @Override
    public void onRelatedAlarmsUpdated(OnmsAlarm alarm, Set<OnmsAlarm> previousRelatedAlarms) {

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
}
