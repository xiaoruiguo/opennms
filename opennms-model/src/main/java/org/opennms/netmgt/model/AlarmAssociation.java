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

package org.opennms.netmgt.model;

import java.io.Serializable;
import java.util.Date;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToOne;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

@Entity
@Table(name = "alarm_situations")
public class AlarmAssociation implements Serializable {

    private static final long serialVersionUID = 4115687014888009683L;

    private Integer id;

    private OnmsAlarm situationAlarm;

    private OnmsAlarm relatedAlarm;

    private Date mappedTime;

    public AlarmAssociation() {
    }

    public AlarmAssociation(OnmsAlarm situationAlarm, OnmsAlarm relatedAlarm) {
        this(situationAlarm, relatedAlarm, new Date());
    }

    public AlarmAssociation(OnmsAlarm situationAlarm, OnmsAlarm relatedAlarm, Date mappedTime) {
        this.mappedTime = mappedTime;
        this.situationAlarm = situationAlarm;
        this.relatedAlarm = relatedAlarm;
    }

    @Id
    @SequenceGenerator(name="alarmSequence", sequenceName="alarmsNxtId")
    @GeneratedValue(generator="alarmSequence")
    @Column(name="id", nullable=false)
    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }


    @ManyToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "situation_id")
    public OnmsAlarm getSituationAlarm() {
        return situationAlarm;
    }

    public void setSituationAlarm(OnmsAlarm situationAlarm) {
        this.situationAlarm = situationAlarm;
    }

    @JoinColumn(name = "related_alarm_id")
    @OneToOne
    public OnmsAlarm getRelatedAlarm() {
        return relatedAlarm;
    }

    public void setRelatedAlarm(OnmsAlarm relatedAlarm) {
        this.relatedAlarm = relatedAlarm;
    }


    public void setMappedTime(Date mappedTime) {
        this.mappedTime = mappedTime;
    }

    @Column(name = "mapped_time")
    @Temporal(TemporalType.TIMESTAMP)
    public Date getMappedTime() {
        return mappedTime;
    }

}
