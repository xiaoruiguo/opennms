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
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.opennms.features.es.alarms.dto.AlarmDocumentDTO;

import io.searchbox.client.JestClient;
import io.searchbox.core.Search;
import io.searchbox.core.SearchResult;

public class AlarmsFromES {

    private final JestClient client;

    public AlarmsFromES(JestClient client) {
        this.client = Objects.requireNonNull(client);
    }

    public List<AlarmDocumentDTO> getAllAlarms() throws IOException {
        final Search search = new Search.Builder("")
                .addIndex("opennms-alarms-*")
                .build();
        final SearchResult result = client.execute(search);
        final List<SearchResult.Hit<AlarmDocumentDTO, Void>> hits = result.getHits(AlarmDocumentDTO.class);
        return hits.stream().map(h -> h.source).collect(Collectors.toList());
    }

}
