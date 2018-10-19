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

import static com.jayway.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.greaterThan;

import java.io.IOException;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.opennms.core.test.MockLogAppender;
import org.opennms.core.test.elastic.ElasticSearchRule;
import org.opennms.core.test.elastic.ElasticSearchServerConfig;
import org.opennms.netmgt.events.api.EventConstants;
import org.opennms.netmgt.model.OnmsAlarm;
import org.opennms.plugins.elasticsearch.rest.RestClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.searchbox.client.JestClient;
import io.searchbox.core.Search;

public class AlarmArchiverIT {
    private static Logger LOG = LoggerFactory.getLogger(AlarmArchiverIT.class);

    private static final String HTTP_PORT = "9205";
    private static final String HTTP_TRANSPORT_PORT = "9305";

    @BeforeClass
    public static void setUpClass() {
        MockLogAppender.setupLogging(true, "DEBUG");
    }

    @Rule
    public ElasticSearchRule elasticSearchRule = new ElasticSearchRule(new ElasticSearchServerConfig()
            .withDefaults()
            .withSetting("http.enabled", true)
            .withSetting("http.port", HTTP_PORT)
            .withSetting("http.type", "netty4")
            .withSetting("transport.type", "netty4")
            .withSetting("transport.tcp.port", HTTP_TRANSPORT_PORT)
    );

    @Test
    public void canArchiveAlarms() throws IOException {
        OnmsAlarm alarm1 = new OnmsAlarm();
        alarm1.setId(1);
        alarm1.setIfIndex(13);
        alarm1.setUei(EventConstants.TOPOLOGY_LINK_DOWN_EVENT_UEI);
        alarm1.setFirstEventTime(new Date());
        alarm1.setLastEventTime(alarm1.getFirstEventTime());

        final RestClientFactory restClientFactory = new RestClientFactory("http://localhost:" + HTTP_PORT);
        try (final JestClient jestClient = restClientFactory.createClient()) {
            final AlarmArchiver alarmArchiver = new AlarmArchiver(jestClient);
            alarmArchiver.start();
            alarmArchiver.onAlarmCreated(alarm1);

            final Search search = new Search.Builder("").addIndex("opennms-alarms-*").build();
            await().atMost(1, TimeUnit.MINUTES).pollInterval(5, TimeUnit.SECONDS)
                    .until(() -> jestClient.execute(search).getTotal(), greaterThan(0L));
        }
    }
}
