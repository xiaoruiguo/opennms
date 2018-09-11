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

package org.opennms.netmgt.daemon;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import org.opennms.core.criteria.CriteriaBuilder;
import org.opennms.core.criteria.restrictions.Restrictions;
import org.opennms.core.utils.ConfigFileConstants;
import org.opennms.core.xml.JaxbUtils;
import org.opennms.netmgt.config.service.Service;
import org.opennms.netmgt.config.service.ServiceConfiguration;
import org.opennms.netmgt.dao.api.EventDao;
import org.opennms.netmgt.events.api.EventConstants;
import org.opennms.netmgt.events.api.EventForwarder;
import org.opennms.netmgt.model.OnmsEvent;
import org.opennms.netmgt.model.events.EventBuilder;
import org.springframework.beans.factory.annotation.Autowired;

public class WorkInProgressDaemonConfigService implements DaemonConfigService {

    @Autowired
    private EventForwarder eventForwarder;

    @Autowired
    private EventDao eventDao;

    private List<DaemonDTO> daemonList = new ArrayList<>();
    private long maxConfigUpdateTime = 5000;

    private Set<String> ignoreList;
    private Set<String> eventDaemons;
    private Set<String> nonReloadableDaemons;

    private Map<String, String> wrongDaemonNameMap;
    private Map<String, String> wrongEventMap;

    public WorkInProgressDaemonConfigService() throws IOException {

        //TODO: Clean up. After equalize the Daemon Behaviour and reworking ServieConfiguration.xml, this methode should be avoidable
        fillDaemonLists();

        //Hacked: Because The ServiceConfigFactory only gives us the enabled ones
        final File cfgFile = ConfigFileConstants.getFile(ConfigFileConstants.SERVICE_CONF_FILE_NAME);
        final ServiceConfiguration config = JaxbUtils.unmarshal(ServiceConfiguration.class, cfgFile);

        for (Service s : config.getServices()) {
            String name = s.getName().split("=")[1].toLowerCase(); //OpenNMS:Name=Manager => Manager => manager
            this.daemonList.add(new DaemonDTO(name, ignoreList.contains(name), s.isEnabled(), eventDaemons.contains(name) && s.isEnabled()));
        }

    }

    private void fillDaemonLists() {
        this.ignoreList = new HashSet<>(Arrays.asList("Manager".toLowerCase(), "TestLoadLibraries".toLowerCase()));

        //The daemons that behave "normal"
        this.eventDaemons = new HashSet<>(Arrays.asList(
                "bsmd",
                "eventd",
                "notifd",
                "pollerd",
                "collectd",
                "discovery",
                "vacuumd",
                "statsd",
                "provisiond",
                "reportd",
                "ackd",
                "telemetryd",

                "pollerbackend",            // seems to work but does not say so
                "scriptd",                  // seems to work but does not say so
                "alarmd",                   // Does nothing but function is called, but does not say so either
                "eventtranslator",          // works with wrong Name = translator
                "ticketer",                 // works with wrong Name = ticketd  And does nothing but function is called

                //normally not enabled
                "tl1d",                     //works
                "snmppoller"                //works with wrong Event but doesn't say so
        ));

        this.wrongDaemonNameMap = new HashMap<>();
        this.wrongDaemonNameMap.put("eventtranslator", "translator");
        this.wrongDaemonNameMap.put("ticketer", "ticketd");

        this.wrongEventMap = new HashMap<>();
        this.wrongEventMap.put("snmppoller", EventConstants.SNMPPOLLERCONFIG_CHANGED_EVENT_UEI);

        this.nonReloadableDaemons = new HashSet<>(Arrays.asList(
                "enhancedlinkd",
                "actiond",
                "trapd",
                "queued",
                "rtcd",
                "jettyserver",
                "passivestatusd",

                //not enabled normally
                "syslogd",
                "correlator",
                "dhcpd",
                "asteriskgateway"
        ));
    }

    @Override
    public DaemonDTO[] getDaemons() {
        DaemonDTO[] rtArray = new DaemonDTO[daemonList.size()];
        daemonList.toArray(rtArray);
        return rtArray;
    }

    @Override
    public boolean reloadDaemon(String daemonName) {

        Optional<DaemonDTO> daemon = this.daemonList.stream()
                .filter(x -> x.getName().equalsIgnoreCase(daemonName))
                .findFirst();

        if(!daemon.isPresent())
            throw new NoSuchElementException();

        if (daemon.get().isReloadable()) {

            String reloadDaemonName = daemonName.toLowerCase();
            String reloadEventName = EventConstants.RELOAD_DAEMON_CONFIG_UEI;

            if (this.wrongEventMap.containsKey(reloadDaemonName)) {
                reloadEventName = this.wrongEventMap.get(reloadDaemonName);
            }
            if (this.wrongDaemonNameMap.containsKey(reloadDaemonName)) {
                reloadDaemonName = this.wrongDaemonNameMap.get(reloadDaemonName);
            }

            EventBuilder eventBuilder = new EventBuilder(reloadEventName, "Admin Daemon Manager");
            eventBuilder.addParam(EventConstants.PARM_DAEMON_NAME, reloadDaemonName);
            this.eventForwarder.sendNow(eventBuilder.getEvent());

            return true;
        }
        return false;
    }

    @Override
    public DaemonReloadStateDTO getDaemonReloadState(String daemonName) {
        Optional<DaemonDTO> daemon = this.daemonList.stream()
                .filter(x -> x.getName().equalsIgnoreCase(daemonName))
                .findFirst();

        if (!daemon.isPresent()) {
            throw new NoSuchElementException();
        }
        String reloadDaemonName = daemonName.toLowerCase();

        if (this.wrongDaemonNameMap.containsKey(reloadDaemonName)) {
            reloadDaemonName = this.wrongDaemonNameMap.get(reloadDaemonName);
        }


        List<OnmsEvent> lastReloadEventList = this.eventDao.findMatching(
                new CriteriaBuilder(OnmsEvent.class)
                        .alias("eventParameters", "eventParameters")
                        .and(
                                Restrictions.eq("eventUei", EventConstants.RELOAD_DAEMON_CONFIG_UEI),
                                Restrictions.eq("eventParameters.name", "daemonName"),
                                Restrictions.ilike("eventParameters.value", daemonName)
                        )
                        .orderBy("eventTime")
                        .desc()
                        .limit(1)
                        .toCriteria()
        );

        if (lastReloadEventList.isEmpty()) {
            return new DaemonReloadStateDTO(null, null, DaemonReloadState.Unknown);
        }

        long lastReloadEventTime = lastReloadEventList.get(0).getEventTime().getTime();

        List<OnmsEvent> lastReloadResultEventList = this.eventDao.findMatching(
                new CriteriaBuilder(OnmsEvent.class)
                        .alias("eventParameters", "params")
                        .and(
                                Restrictions.or(
                                        Restrictions.eq("eventUei", EventConstants.RELOAD_DAEMON_CONFIG_SUCCESSFUL_UEI),
                                        Restrictions.eq("eventUei", EventConstants.RELOAD_DAEMON_CONFIG_FAILED_UEI)
                                ),
                                Restrictions.ilike("params.value", daemonName),
                                Restrictions.eq("params.name", "daemonName"),
                                Restrictions.gt("eventTime", new Date(lastReloadEventTime)),
                                Restrictions.lt("eventTime", new Date(lastReloadEventTime + maxConfigUpdateTime))
                        )
                        .orderBy("eventTime")
                        .limit(10)
                        .toCriteria()
        );

        if (lastReloadResultEventList.size() < 1)
            return new DaemonReloadStateDTO(lastReloadEventTime, null, DaemonReloadState.Reloading);

        OnmsEvent lastReloadResultEvent = lastReloadResultEventList.get(0);
        if (lastReloadResultEvent.getEventUei().equals(EventConstants.RELOAD_DAEMON_CONFIG_SUCCESSFUL_UEI)) {
            return new DaemonReloadStateDTO(lastReloadEventTime, lastReloadResultEvent.getEventTime().getTime(), DaemonReloadState.Success);
        } else {
            return new DaemonReloadStateDTO(lastReloadEventTime, lastReloadResultEvent.getEventTime().getTime(), DaemonReloadState.Failed);
        }
    }
}
