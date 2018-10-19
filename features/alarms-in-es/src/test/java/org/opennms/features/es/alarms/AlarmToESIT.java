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
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.opennms.core.test.alarms.AlarmMatchers.hasSeverity;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.opennms.core.test.alarms.driver.Scenario;
import org.opennms.core.test.alarms.driver.ScenarioResults;
import org.opennms.core.test.alarms.driver.State;
import org.opennms.core.test.elastic.ElasticSearchRule;
import org.opennms.core.test.elastic.ElasticSearchServerConfig;
import org.opennms.netmgt.model.OnmsSeverity;
import org.opennms.plugins.elasticsearch.rest.RestClientFactory;

import io.searchbox.client.JestClient;
import io.searchbox.core.Search;

public class AlarmToESIT {
    private static final String HTTP_PORT = "9205";
    private static final String HTTP_TRANSPORT_PORT = "9305";

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
        ScenarioResults results = null;
        final RestClientFactory restClientFactory = new RestClientFactory("http://localhost:" + HTTP_PORT);
        try (final JestClient jestClient = restClientFactory.createClient()) {
            Scenario scenario = Scenario.builder()
                    .withLegacyAlarmBehavior()
                    .withNodeDownEvent(1, 1)
                    .withNodeUpEvent(2, 1)
                    .awaitUntil(() -> {
                        final Search search = new Search.Builder("").addIndex("opennms-alarms-*").build();
                        await().atMost(1, TimeUnit.MINUTES).pollInterval(5, TimeUnit.SECONDS)
                                .until(() -> jestClient.execute(search).getTotal(), greaterThan(0L));
                    })
                    .build();
            results = scenario.play();
        }

        // Verify the set of alarms at various points in time

        // t=0, no alarms
        assertThat(results.getAlarms(0), hasSize(0));
        // t=1, a single problem alarm
        assertThat(results.getAlarms(1), hasSize(1));
        assertThat(results.getProblemAlarm(1), hasSeverity(OnmsSeverity.MAJOR));
        // t=2, a (cleared) problem and a resolution
        assertThat(results.getAlarms(2), hasSize(2));
        assertThat(results.getProblemAlarm(2), hasSeverity(OnmsSeverity.CLEARED));
        assertThat(results.getResolutionAlarm(2), hasSeverity(OnmsSeverity.NORMAL));
        // t=∞
        assertThat(results.getAlarmsAtLastKnownTime(), hasSize(0));

        // Now verify the state changes for the particular alarms

        // the problem
        List<State> problemStates = results.getStateChangesForAlarmWithId(results.getProblemAlarm(1).getId());
        assertThat(problemStates, hasSize(3)); // warning, cleared, deleted
        // state 0 at t=1
        assertThat(problemStates.get(0).getTime(), equalTo(1L));
        assertThat(problemStates.get(0).getAlarm(), hasSeverity(OnmsSeverity.MAJOR));
        // state 1 at t=2
        assertThat(problemStates.get(1).getTime(), equalTo(2L));
        assertThat(problemStates.get(1).getAlarm(), hasSeverity(OnmsSeverity.CLEARED));
        assertThat(problemStates.get(1).getAlarm().getCounter(), equalTo(1));
        // state 2 at t in [5m2ms, 10m]
        assertThat(problemStates.get(2).getTime(), greaterThanOrEqualTo(2L + TimeUnit.MINUTES.toMillis(5)));
        assertThat(problemStates.get(2).getTime(), lessThan(TimeUnit.MINUTES.toMillis(10)));
        assertThat(problemStates.get(2).getAlarm(), nullValue()); // DELETED

        // the resolution
        List<State> resolutionStates = results.getStateChangesForAlarmWithId(results.getResolutionAlarm(2).getId());
        assertThat(resolutionStates, hasSize(2)); // cleared, deleted
        // state 0 at t=2
        assertThat(resolutionStates.get(0).getTime(), equalTo(2L));
        assertThat(resolutionStates.get(0).getAlarm(), hasSeverity(OnmsSeverity.NORMAL));
        // state 1 at t in [5m2ms, 10m]
        assertThat(resolutionStates.get(1).getTime(), greaterThanOrEqualTo(2L + TimeUnit.MINUTES.toMillis(5)));
        assertThat(resolutionStates.get(1).getTime(), lessThan(TimeUnit.MINUTES.toMillis(10)));
        assertThat(resolutionStates.get(1).getAlarm(), nullValue()); // DELETED
    }
}
