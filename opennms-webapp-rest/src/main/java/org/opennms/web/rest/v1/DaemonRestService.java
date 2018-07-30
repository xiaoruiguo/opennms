/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2008-2014 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2014 The OpenNMS Group, Inc.
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

package org.opennms.web.rest.v1;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

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
import org.opennms.web.rest.v1.support.DaemonDTO;
import org.opennms.web.rest.v1.support.DaemonReloadState;
import org.opennms.web.rest.v1.support.DaemonReloadStateDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component("daemonRestService")
@Path("daemons")
public class DaemonRestService extends OnmsRestService {
    @Autowired
    private EventForwarder eventForwarder;

    @Autowired
    private EventDao eventDao;

    private long maxConfigUpdateTime = 5 * 1000;

    private Map<String, Long> lastReloadTimeStempMap = new HashMap<>();

    private static final Logger LOG = LoggerFactory.getLogger(DaemonRestService.class);

    private List<DaemonDTO> daemonList = new ArrayList<>();




    private Set<String> ignoreList = new HashSet<>(Arrays.asList(
            "Manager".toLowerCase(), "TestLoadLibraries".toLowerCase()));

    private Set<String> eventDaemons;
    private Set<String> nonReloadableDaemons;

    private Map<String, String> wrongDaemonNameMap;
    private Map<String, String> wrongEventMap;

    public DaemonRestService() throws IOException {

        fillDaemonLists();

        //Hacked: Because The ServiceConfigFactory only gives us the enabled ones
        final File cfgFile = ConfigFileConstants.getFile(ConfigFileConstants.SERVICE_CONF_FILE_NAME);
        final ServiceConfiguration config = JaxbUtils.unmarshal(ServiceConfiguration.class, cfgFile);

        for (Service s : config.getServices()) {
            String name = s.getName().split("=")[1].toLowerCase(); //OpenNMS:Name=Manager => Manager => manager
            this.daemonList.add(new DaemonDTO(name, ignoreList.contains(name), s.isEnabled(), eventDaemons.contains(name) && s.isEnabled()));
            this.lastReloadTimeStempMap.put(name, new Date().getTime() - maxConfigUpdateTime);
        }

    }

    /*
     *   TODO:
     *   A little Work in Progress-Method to fill the DaemonNames in Lists, dependant on how and if their Configs can be reloaded.
     *   It is only necessary to do so until:
      *  - Every daemon is reloaded the same way.
      *  - The service-configuration knows about if the daemon is reloadable.
     */
    private void fillDaemonLists() {

        //The daemons that behave "normal"
        eventDaemons = new HashSet<>(Arrays.asList(
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

        wrongDaemonNameMap = new HashMap<>();
        wrongDaemonNameMap.put("eventtranslator", "translator");
        wrongDaemonNameMap.put("ticketer", "ticketd");

        wrongEventMap = new HashMap<>();
        wrongEventMap.put("snmppoller", EventConstants.SNMPPOLLERCONFIG_CHANGED_EVENT_UEI);

        nonReloadableDaemons = new HashSet<>(Arrays.asList(
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

    /**
     * <p>getDaemons</p>
     *
     * @return a {@link DaemonDTO} Array.
     */
    @GET
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, MediaType.APPLICATION_ATOM_XML})
    public DaemonDTO[] getDaemons(@Context final UriInfo uriInfo) {
        DaemonDTO[] rtArray = new DaemonDTO[daemonList.size()];
        daemonList.toArray(rtArray);
        return rtArray;
    }

    /**
     *
     * @param daemonName
     * @return 204 if the daemon with the given name was found and reloaded,
     * 429 if the daemon is to fast reloaded
     * 404 if the daemon is not found
     * 428 if the daemon is not reloadable
     */
    @POST
    @Path("reload/{daemonName}")
    public Response reloadDaemonByName(@PathParam("daemonName") String daemonName) {

        Response rtResponse;

        if (eventDaemons.contains(daemonName.toLowerCase())) {
            if (new Date().getTime() - this.lastReloadTimeStempMap.get(daemonName.toLowerCase()) > maxConfigUpdateTime) {

                this.lastReloadTimeStempMap.put(daemonName.toLowerCase(), new Date().getTime());

                String reloadDaemonName = daemonName.toLowerCase();
                String reloadEventName = EventConstants.RELOAD_DAEMON_CONFIG_UEI;

                if (wrongEventMap.containsKey(reloadDaemonName)) {
                    reloadEventName = wrongEventMap.get(reloadDaemonName);
                }
                if (wrongDaemonNameMap.containsKey(reloadDaemonName)) {
                    reloadDaemonName = wrongDaemonNameMap.get(reloadDaemonName);
                }


                EventBuilder eventBuilder = new EventBuilder(reloadEventName, "Admin Daemon Manager");
                eventBuilder.addParam(EventConstants.PARM_DAEMON_NAME, reloadDaemonName);
                eventForwarder.sendNow(eventBuilder.getEvent());


                rtResponse = Response.noContent().build();
                return rtResponse;
            } else {
                rtResponse = Response.status(429 /* too frequent */).build();
                return rtResponse;
            }
        }

        if (nonReloadableDaemons.contains(daemonName.toLowerCase())) {
            rtResponse = Response.status(428 /* Precondition Required */).build();
            return rtResponse;

        }

        rtResponse = Response.status(404 /* not found */).build();
        return rtResponse;
    }

    /**
     *
     * @param daemonName
     * @return Gets a {@link DaemonReloadStateDTO}
     * or null if the name does not exist
     */
    @GET
    @Path("checkReloadState/{daemonName}")
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML, MediaType.APPLICATION_ATOM_XML})
    public DaemonReloadStateDTO getDaemonReloadState(@PathParam("daemonName") String daemonName) {

        final String daemonNameRaw = daemonName;

        Optional<DaemonDTO> daemon = daemonList.stream()
                .filter(x -> x.getName().equalsIgnoreCase(daemonNameRaw))
                .findFirst();

        if (!daemon.isPresent())
            return null;

        if (wrongDaemonNameMap.containsKey(daemonName)) {
            daemonName = wrongDaemonNameMap.get(daemonName);
        }


        List<OnmsEvent> lastReloadEventList = eventDao.findMatching(
                new CriteriaBuilder(OnmsEvent.class)
                        .alias("eventParameters", "params")
                        .and(
                                Restrictions.eq("eventUei", EventConstants.RELOAD_DAEMON_CONFIG_UEI),
                                Restrictions.eq("params.name", "daemonName"),
                                Restrictions.ilike("params.value", daemonName)
                        )
                        .orderBy("eventTime")
                        .desc()
                        .limit(1)
                        .toCriteria()
        );

        if (lastReloadEventList.size() < 1) {
            return new DaemonReloadStateDTO(null, null, DaemonReloadState.Unknown);
        }

        long lastReloadEventTime = lastReloadEventList.get(0).getEventTime().getTime();


        List<OnmsEvent> le = eventDao.findMatching(
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

        if (le.size() < 1)
            return new DaemonReloadStateDTO(lastReloadEventTime, null, DaemonReloadState.Reloading);

        OnmsEvent ev = le.get(0); //
        if (ev.getEventUei().equals(EventConstants.RELOAD_DAEMON_CONFIG_SUCCESSFUL_UEI)) {
            return new DaemonReloadStateDTO(lastReloadEventTime, ev.getEventTime().getTime(), DaemonReloadState.Success);
        } else {
            return new DaemonReloadStateDTO(lastReloadEventTime, ev.getEventTime().getTime(), DaemonReloadState.Failed);
        }
    }
}
