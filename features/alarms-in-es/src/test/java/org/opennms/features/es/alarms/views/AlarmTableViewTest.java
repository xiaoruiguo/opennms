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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;

import org.junit.Test;
import org.opennms.features.es.alarms.dto.AlarmDocumentDTO;
import org.opennms.features.es.alarms.dto.RelatedAlarmDocumentDTO;
import org.opennms.features.es.alarms.dto.RelatedAlarmStateChangeDTO;

public class AlarmTableViewTest {

    /**
     * Validates that we can infer the set of related alarms at any given time
     * based on the current state and the changelog.
     */
    @Test
    public void canDetermineRelatedAlarms() {
        // No related alarms, no changes
        AlarmDocumentDTO doc = new AlarmDocumentDTO();
        AlarmDocumentView view = new AlarmDocumentView(doc, 0);
        assertThat(view.getReductionKeysForRelatedAlarms(), hasSize(0));

        // One related alarm, no changes
        doc = new AlarmDocumentDTO();
        RelatedAlarmDocumentDTO r1 = new RelatedAlarmDocumentDTO();
        r1.setId(1);
        r1.setReductionKey("r1");
        doc.addRelatedAlarm(r1);
        view = new AlarmDocumentView(doc, 0);
        assertThat(view.getReductionKeysForRelatedAlarms(), contains("r1"));

        // One existing since creation, one comes and goes, and another stays
        doc = new AlarmDocumentDTO();
        // initial
        r1 = new RelatedAlarmDocumentDTO();
        r1.setId(1);
        r1.setReductionKey("r1");
        doc.addRelatedAlarm(r1);
        // addition
        doc.addRelatedAlarmStateChange(new RelatedAlarmStateChangeDTO(1L, 2, "r2", true));
        // removal
        doc.addRelatedAlarmStateChange(new RelatedAlarmStateChangeDTO(2L, 2, "r2", false));
        // addition & current
        doc.addRelatedAlarmStateChange(new RelatedAlarmStateChangeDTO(3L, 3, "r3", true));
        RelatedAlarmDocumentDTO r3 = new RelatedAlarmDocumentDTO();
        r3.setId(3);
        r3.setReductionKey("r3");
        doc.addRelatedAlarm(r3);

        view = new AlarmDocumentView(doc, 0);
        assertThat(view.getReductionKeysForRelatedAlarms(), contains("r1"));
        view = new AlarmDocumentView(doc, 1);
        assertThat(view.getReductionKeysForRelatedAlarms(), containsInAnyOrder("r1", "r2"));
        view = new AlarmDocumentView(doc, 2);
        assertThat(view.getReductionKeysForRelatedAlarms(), contains("r1"));
        view = new AlarmDocumentView(doc, 3);
        assertThat(view.getReductionKeysForRelatedAlarms(), containsInAnyOrder("r1", "r3"));
    }
}
