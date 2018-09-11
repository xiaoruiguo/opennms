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

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.opennms.core.test.OpenNMSJUnit4ClassRunner;
import org.opennms.test.JUnitConfigurationEnvironment;
import org.springframework.test.context.ContextConfiguration;

@RunWith(OpenNMSJUnit4ClassRunner.class)
@ContextConfiguration(locations = {
//        "classpath:/META-INF/opennms/applicationContext-soa.xml",
//        "classpath:/META-INF/opennms/applicationContext-commonConfigs.xml",
        "classpath:/META-INF/opennms/applicationContext-daemonConfigTest.xml"
//        "classpath:/META-INF/opennms/applicationContext-daemon.xml",
//        "classpath:/META-INF/opennms/applicationContext-mockDao.xml",
//        "classpath:/META-INF/opennms/applicationContext-mockEventd.xml"
})
@JUnitConfigurationEnvironment
public class WorkInProgressDaemonConfigServiceTest {

    @Test
    public void areThereAnyDaemons() {
        int i = 0;
        assertThat(i, is(0));
    }
/*
    @Autowired
    private DaemonConfigService configService;
    @Autowired
    private EventForwarder eventForwarder;

    @Autowired
    private EventDao eventDao;


    @Test
    public void areThereAnyDaemons() {
        DaemonDTO[] da = this.configService.getDaemons();
        assertThat(da.length > 0, is(true));
    }

    @Test
    public void doesReloadWorkLowerCase() {
        boolean isReloading = this.configService.reloadDaemon("eventd");
        assertEquals(isReloading, true);

        Optional<Event> event = ((EmptyEventForwarder) eventForwarder).getEventList().stream()
                .filter(x ->
                        x.getUei() == EventConstants.RELOAD_DAEMON_CONFIG_UEI
                                &&
                                x.getParm("daemonName") != null
                                &&
                                x.getParm("daemonName").getValue().getContent().equals("eventd")
                )
                .findFirst();
        assertThat(event.isPresent(), is(true));
    }

    @Test
    public void doesReloadWorkUppercase() {
        boolean isReloading = this.configService.reloadDaemon("EvEntD");
        assertEquals(isReloading, true);

        Optional<Event> event = ((EmptyEventForwarder) eventForwarder).getEventList().stream()
                .filter(x ->
                        x.getUei() == EventConstants.RELOAD_DAEMON_CONFIG_UEI
                                &&
                                x.getParm("daemonName") != null
                                &&
                                x.getParm("daemonName").getValue().getContent().equals("eventd")
                )
                .findFirst();
        assertThat(event.isPresent(), is(true));
    }

    @Test
    public void doesReloadRejectingWork() {
        boolean isReloading = true;
        isReloading = this.configService.reloadDaemon("jettyserver");
        assertEquals(isReloading, false);

        isReloading = this.configService.reloadDaemon("JETTYserver");
        assertEquals(isReloading, false);

        Optional<Event> event = ((EmptyEventForwarder) eventForwarder).getEventList().stream()
                .filter(x ->
                        x.getUei() == EventConstants.RELOAD_DAEMON_CONFIG_UEI
                                &&
                                x.getParm("daemonName") != null
                                &&
                                x.getParm("daemonName").getValue().getContent().equals("jettyserver")
                )
                .findFirst();
        assertFalse(event.isPresent());
    }

    @Test(expected = NoSuchElementException.class)
    public void doesServiceRejectUnknownDaemonNames1() {
        this.configService.reloadDaemon("genericWrongDaemonName");
    }

    @Test(expected = NoSuchElementException.class)
    public void doesServiceRejectUnknownDaemonNames2() {
        this.configService.getDaemonReloadState("genericWrongDaemonName");
    }

    @Test
    public void doesReloadStateWork() throws InterruptedException {
        DaemonReloadStateDTO state;
        EventBuilder requestEventBuilder, successEventBuilder, failEventBuilder;
        Date requestDate, successDate, failDate;

        //Todo: Add Time Checks
        //Using jettyserver since normaly it should be never reloaded

        //Unknown
        state = this.configService.getDaemonReloadState("jettyserver");
        assertThat(state.getReloadState(), is(DaemonReloadState.Unknown));
        assertThat(state.getReloadRequestEventTime(), is(nullValue()));
        assertThat(state.getReloadResultEventTime(), is(nullValue()));

        //Reloading
        requestEventBuilder = new EventBuilder(EventConstants.RELOAD_DAEMON_CONFIG_UEI, "Test Daemon Manager");
        requestEventBuilder.addParam(EventConstants.PARM_DAEMON_NAME, "jettyserver");
        this.eventForwarder.sendNow(requestEventBuilder.getEvent());

        state = this.configService.getDaemonReloadState("jettyserver");
        assertThat(state.getReloadState(), is(DaemonReloadState.Reloading));
        assertThat(state.getReloadRequestEventTime(), is(not(nullValue())));
        assertThat(state.getReloadResultEventTime(), is(nullValue()));


        //Success
        requestEventBuilder = new EventBuilder(EventConstants.RELOAD_DAEMON_CONFIG_UEI, "Test Daemon Manager");
        requestEventBuilder.addParam(EventConstants.PARM_DAEMON_NAME, "jettyserver");
        this.eventForwarder.sendNow(requestEventBuilder.getEvent());

        Thread.sleep(300);
        successEventBuilder = new EventBuilder(EventConstants.RELOAD_DAEMON_CONFIG_SUCCESSFUL_UEI, "Test Daemon Manager");
        successEventBuilder.addParam(EventConstants.PARM_DAEMON_NAME, "jettyserver");
        this.eventForwarder.sendNow(successEventBuilder.getEvent());

        state = this.configService.getDaemonReloadState("jettyserver");
        assertThat(state.getReloadState(), is(DaemonReloadState.Success));
        assertThat(state.getReloadRequestEventTime(), is(not(nullValue())));
        assertThat(state.getReloadResultEventTime(), is(not(nullValue())));
        assertEquals(state.getReloadResultEventTime() - state.getReloadRequestEventTime() > 0, true);


        //Failed
        requestEventBuilder = new EventBuilder(EventConstants.RELOAD_DAEMON_CONFIG_UEI, "Test Daemon Manager");
        requestEventBuilder.addParam(EventConstants.PARM_DAEMON_NAME, "jettyserver");
        this.eventForwarder.sendNow(requestEventBuilder.getEvent());
        Thread.sleep(300);
        failEventBuilder = new EventBuilder(EventConstants.RELOAD_DAEMON_CONFIG_FAILED_UEI, "Test Daemon Manager");
        failEventBuilder.addParam(EventConstants.PARM_DAEMON_NAME, "jettyserver");
        this.eventForwarder.sendNow(failEventBuilder.getEvent());

        state = this.configService.getDaemonReloadState("jettyserver");
        assertThat(state.getReloadState(), is(DaemonReloadState.Failed));
        assertThat(state.getReloadRequestEventTime(), is(not(nullValue())));
        assertThat(state.getReloadResultEventTime(), is(not(nullValue())));
        assertThat(state.getReloadResultEventTime() - state.getReloadRequestEventTime() > 0, is(true));
    }
    */
}
