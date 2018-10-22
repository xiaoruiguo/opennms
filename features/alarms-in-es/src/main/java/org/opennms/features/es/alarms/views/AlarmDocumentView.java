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

package org.opennms.features.es.alarms.views;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.opennms.features.es.alarms.dto.AckStateChangeDTO;
import org.opennms.features.es.alarms.dto.AlarmDocumentDTO;
import org.opennms.features.es.alarms.dto.RelatedAlarmDocumentDTO;
import org.opennms.features.es.alarms.dto.RelatedAlarmStateChangeDTO;
import org.opennms.features.es.alarms.dto.SeverityStateChangeDTO;
import org.opennms.features.es.alarms.dto.StateChangeDTO;
import org.opennms.netmgt.model.OnmsSeverity;

/**
 * Given a {@link AlarmDocumentDTO} this class can be used to render a
 * view of the alarm at some given time.
 *
 * @author jwhite
 */
public class AlarmDocumentView {

    private final AlarmDocumentDTO alarm;
    private final long time;

    public AlarmDocumentView(AlarmDocumentDTO alarm, long time) {
        this.alarm = Objects.requireNonNull(alarm);
        this.time = time;
    }

    public OnmsSeverity getSeverity() {
        return OnmsSeverity.get(getLastSeverityStateChange()
                .map(SeverityStateChangeDTO::getSeverityId)
                .orElse(alarm.getSeverityId()));
    }

    public Long getAckTime() {
        final Optional<AckStateChangeDTO> lastAckStateChange = getLastAckStateChange();
        // NOTE: We can't use Optional#map here since the result is nullable
        if (lastAckStateChange.isPresent()) {
            return lastAckStateChange.get().getAckTime();
        } else {
            return alarm.getAckTime();
        }
    }

    public String getAckUser() {
        final Optional<AckStateChangeDTO> lastAckStateChange = getLastAckStateChange();
        // NOTE: We can't use Optional#map here since the result is nullable
        if (lastAckStateChange.isPresent()) {
            return lastAckStateChange.get().getAckUser();
        } else {
            return alarm.getAckUser();
        }
    }

    private static class RelatedAlarmState {
        private Long addedAt;
        private Long removedAt;
        private Integer id;
        private String reductionKey;
    }

    public Set<String> getReductionKeysForRelatedAlarms() {
        // Group the state changes by reduction key
        final Map<String, List<RelatedAlarmStateChangeDTO>> stateChangesByReductionKey;
        if (alarm.getRelatedAlarmStateChanges() != null) {
            stateChangesByReductionKey = alarm.getRelatedAlarmStateChanges()
                    .stream()
                    .collect(Collectors.groupingBy(RelatedAlarmStateChangeDTO::getReductionKey));
        } else {
            stateChangesByReductionKey = new HashMap<>();
        }

        // Gather the set of latest related reduction keys
        final Set<String> latestReductionKeys = new LinkedHashSet<>();
        final List<RelatedAlarmDocumentDTO> relatedAlarms = alarm.getRelatedAlarms();
        if (relatedAlarms != null) {
            relatedAlarms.forEach(r -> latestReductionKeys.add(r.getReductionKey()));
        }

        final Set<String> allKnownRelatedReductionKeys = new HashSet<>();
        allKnownRelatedReductionKeys.addAll(stateChangesByReductionKey.keySet());
        allKnownRelatedReductionKeys.addAll(latestReductionKeys);

        final Set<String> currentReductionKeys = new HashSet<>();
        for (String knownRelatedReductionKey : allKnownRelatedReductionKeys) {
            // Find the first state change that is greater than the given time
            final Optional<RelatedAlarmStateChangeDTO> stateChange = stateChangesByReductionKey
                    .getOrDefault(knownRelatedReductionKey, Collections.emptyList()).stream()
                    .sorted(Comparator.comparing(StateChangeDTO::getTime))
                    .filter(s -> s.getTime() > time)
                    .findFirst();
            if (stateChange.isPresent()) {
                if (stateChange.get().isRemoval()) {
                    // It was removed later, so it was present at the current time
                    currentReductionKeys.add(knownRelatedReductionKey);
                } // else, it was added later, do not add it to the current set
            } else if (latestReductionKeys.contains(knownRelatedReductionKey)) {
                // There are no more state changes, only add it if it is part of the current set
                currentReductionKeys.add(knownRelatedReductionKey);
            }
        }
        return currentReductionKeys;
    }

    private Optional<SeverityStateChangeDTO> getLastSeverityStateChange() {
        return getLastStateChange(alarm.getSeverityStateChanges());
    }

    private Optional<AckStateChangeDTO> getLastAckStateChange() {
        return getLastStateChange(alarm.getAckStateChanges());
    }

    private <S extends StateChangeDTO> Optional<S> getLastStateChange(Collection<S> stateChanges) {
        if (stateChanges == null) {
            return Optional.empty();
        }
        // Find the first state change that is greater than the given time
        return stateChanges.stream()
                .sorted(Comparator.comparing(StateChangeDTO::getTime))
                .filter(s -> s.getTime() > time)
                .findFirst();
    }

    @Override
    public String toString() {
        return String.format("AlarmDocumentView[t=%d, severity=%s, ackTime=%d, ackUser=%s]",
                time, getSeverity(), getAckTime(), getAckUser());
    }


}
