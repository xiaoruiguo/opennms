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

/*
public class EmptyEventDao implements EventDao {

    private static List<OnmsEvent> eventList = new ArrayList<>();

    @Override
    public int deletePreviousEventsForAlarm(Integer id, OnmsEvent e) {
        return 0;
    }

    @Override
    public List<OnmsEvent> getEventsAfterDate(List<String> ueiList, Date date) {
        return null;
    }

    @Override
    public List<OnmsEvent> getEventsForEventParameters(Map<String, String> eventParameters) {
        return null;
    }

    @Override
    public List<OnmsEvent> findMatching(OnmsCriteria criteria) {
        return null;
    }

    @Override
    public int countMatching(OnmsCriteria onmsCrit) {
        return 0;
    }

    @Override
    public void lock() {

    }

    @Override
    public void initialize(Object obj) {

    }

    @Override
    public void flush() {

    }

    @Override
    public void clear() {
        eventList.clear();
    }

    @Override
    public int countAll() {
        return 0;
    }

    @Override
    public void delete(OnmsEvent entity) {

    }

    @Override
    public void delete(Integer key) {

    }

    @Override
    public List<OnmsEvent> findAll() {
        return eventList;
    }

    public void addEvent(OnmsEvent event) {

    }

    @Override
    public List<OnmsEvent> findMatching(Criteria criteria) {
//        final BeanWrapperCriteriaVisitor visitor = new BeanWrapperCriteriaVisitor(findAll());
//        criteria.visit(visitor);
//
//        visitor.getMatches();
//
//        final Collection<OnmsEvent> matches = (Collection<OnmsEvent>)visitor.getMatches();
//        return new ArrayList<>(matches);
        Set<String> stringSet = getEventUeiStrings(criteria);
        String daemonName = getDaemonName(criteria);

        List<OnmsEvent> l = findAll().stream()
                .sorted((p1, p2) -> p2.getEventTime().compareTo(p1.getEventTime()))
                .filter(x ->
                                stringSet.contains(x.getEventUei())
                                        &&
                                !x.getEventParameters().stream().filter(
                                        y ->
                                                y.getName().equals("daemonName")
                                                    &&
                                                y.getValue().equalsIgnoreCase(daemonName)).collect(Collectors.toList()
                                        ).isEmpty()

                )
                .collect(Collectors.toList());
        return l;
    }

    private String getDaemonName(Criteria criteria){
        List<Restriction> rootRestriction = new ArrayList<>(criteria.getRestrictions());
        if(rootRestriction.isEmpty())
            return null;

        Collection<Restriction> restrictions = ((AllRestriction) rootRestriction.get(0)).getRestrictions();
        List<String> ilikes = restrictions.stream()
                .filter(x -> x.getType() == Restriction.RestrictionType.ILIKE)
                .map(x -> (String) ((IlikeRestriction) x).getValue())
                .collect(Collectors.toList());

        if(ilikes.isEmpty())
            return null;
        return ilikes.get(0);
    }

    private Set<String> getEventUeiStrings(Criteria criteria){

        List<Restriction> rootRestriction = new ArrayList<>(criteria.getRestrictions());

        if(rootRestriction.isEmpty())
            return new HashSet<>();

        Collection<Restriction> restrictions = ((AllRestriction) rootRestriction.get(0)).getRestrictions();

        List<EqRestriction> eqRestrictions = restrictions.stream()
                .filter(x -> x.getType() == Restriction.RestrictionType.EQ)
                .map(x -> (EqRestriction) x)
                .collect(Collectors.toList());

        List<AnyRestriction> orRestricions = restrictions.stream()
                .filter(x -> x.getType() == Restriction.RestrictionType.ANY)
                .map(x -> (AnyRestriction) x)
                .collect(Collectors.toList());

        if (!orRestricions.isEmpty()) {
            Collection<Restriction> r4 = orRestricions.get(0).getRestrictions();
            Collection<EqRestriction> r5 = r4.stream()
                    .filter(x -> x.getType() == Restriction.RestrictionType.EQ)
                    .map(x -> (EqRestriction) x)
                    .collect(Collectors.toList());
            eqRestrictions.addAll(r5);
        }

        Set<String> stringSet = eqRestrictions.stream()
                .filter(x -> x.getAttribute().equals("eventUei"))
                .map(x -> (String) x.getValue())
                .collect(Collectors.toSet());
        return stringSet;
    }

    @Override
    public int countMatching(Criteria onmsCrit) {
        return 0;
    }

    @Override
    public OnmsEvent get(Integer id) {
        return null;
    }

    @Override
    public OnmsEvent load(Integer id) {
        return null;
    }

    @Override
    public Integer save(OnmsEvent entity) {
        eventList.add(entity);
        return eventList.size() - 1;
    }

    @Override
    public void saveOrUpdate(OnmsEvent entity) {

    }

    @Override
    public void update(OnmsEvent entity) {

    }
}
*/