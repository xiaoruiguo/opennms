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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.opennms.features.es.alarms.dto.AlarmDocumentDTO;
import org.opennms.netmgt.model.OnmsAlarm;

/**
 * Helps view a set of alarms as they would have appeared at the given time.
 */
public class AlarmTableView {

    private final List<AlarmDocumentDTO> alarms;

    public AlarmTableView(List<AlarmDocumentDTO> alarms) {
        this.alarms = new ArrayList<>(alarms);
    }

    /**
     * Return the set of alarms which were created, but not yet deleted at the given time.
     *
     * @param time timestamp in ms
     * @return list of alarms
     */
    public List<AlarmDocumentDTO> getAlarmsAtTime(long time) {
        return alarms.stream()
                .filter(a -> a.getFirstEventTime() <= time)
                .filter(a -> a.getDeletedTime() == null || a.getDeletedTime() > time)
                .sorted(Comparator.comparing(AlarmDocumentDTO::getFirstEventTime))
                .collect(Collectors.toList());
    }

    public AlarmDocumentView getProblemAlarmAtTime(long t) {
        return new AlarmDocumentView(getFirstAlarmWithType(t, OnmsAlarm.PROBLEM_TYPE, "problem"), t);
    }

    public AlarmDocumentView getResolutionAlarmAtTime(long t) {
        return new AlarmDocumentView(getFirstAlarmWithType(t, OnmsAlarm.RESOLUTION_TYPE, "resolution"), t);
    }

    private AlarmDocumentDTO getFirstAlarmWithType(long time, int type, String typeDescr) {
        return getAlarmsAtTime(time).stream()
                .filter(a -> a.getAlarmType() == type)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No " + typeDescr + " alarms at time: " + time));
    }

    public List<AlarmDocumentDTO> getSituationsAtTime(long time) {
        return getAlarmsAtTime(time).stream()
                .filter(AlarmDocumentDTO::isSituation)
                .collect(Collectors.toList());
    }

    public AlarmDocumentView getSituationAtTime(long time) {
        return getSituationsAtTime(time).stream()
                .findFirst()
                .map(s -> new AlarmDocumentView(s, time))
                .orElse(null);
    }
}
