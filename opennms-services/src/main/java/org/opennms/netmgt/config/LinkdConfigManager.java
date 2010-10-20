/*
 * This file is part of the OpenNMS(R) Application.
 *
 * OpenNMS(R) is Copyright (C) 2006 The OpenNMS Group, Inc.  All rights reserved.
 * OpenNMS(R) is a derivative work, containing both original code, included code and modified
 * code that was published under the GNU General Public License. Copyrights for modified
 * and included code are below.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * Modifications:
 * 
 * Created: July 26, 2006
 *
 * Copyright (C) 2006 The OpenNMS Group, Inc.  All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 *
 * For more information contact:
 *      OpenNMS Licensing       <license@opennms.org>
 *      http://www.opennms.org/
 *      http://www.opennms.com/
 */
package org.opennms.netmgt.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.exolab.castor.xml.MarshalException;
import org.exolab.castor.xml.Marshaller;
import org.exolab.castor.xml.ValidationException;
import org.opennms.core.utils.InetAddressUtils;
import org.opennms.core.utils.IpListFromUrl;
import org.opennms.core.utils.LogUtils;
import org.opennms.netmgt.config.linkd.ExcludeRange;
import org.opennms.netmgt.config.linkd.Filter;
import org.opennms.netmgt.config.linkd.IncludeRange;
import org.opennms.netmgt.config.linkd.Iproutes;
import org.opennms.netmgt.config.linkd.LinkdConfiguration;
import org.opennms.netmgt.config.linkd.Package;
import org.opennms.netmgt.config.linkd.Vendor;
import org.opennms.netmgt.config.linkd.Vlans;
import org.opennms.netmgt.dao.castor.CastorUtils;
import org.opennms.netmgt.filter.FilterDaoFactory;
import org.opennms.netmgt.linkd.DiscoveryLink;
import org.opennms.netmgt.linkd.SnmpCollection;
import org.opennms.protocols.snmp.SnmpObjectId;

/**
 * <p>Abstract LinkdConfigManager class.</p>
 *
 * @author <a href="mailto:antonio@opennms.it">Antonio Russo</a>
 * @version $Id: $
 */
abstract public class LinkdConfigManager implements LinkdConfig {
    private final ReadWriteLock m_globalLock = new ReentrantReadWriteLock();
    private final Lock m_readLock = m_globalLock.readLock();
    private final Lock m_writeLock = m_globalLock.writeLock();
    
	private static final String DEFAULT_IP_ROUTE_CLASS_NAME = "org.opennms.netmgt.linkd.snmp.IpRouteTable";

    /**
	 * Object containing all Linkd-configuration objects parsed from the XML
	 * file
	 */
	protected static LinkdConfiguration m_config;

    /**
     * A mapping of the configured URLs to a list of the specific IPs configured
     * in each - so as to avoid file reads
     */
    private static Map<String, List<String>> m_urlIPMap = new HashMap<String, List<String>>();

    /**
     * A mapping of the configured package to a list of IPs selected via filter
     * rules, so as to avoid redundant database access.
     */
    private static Map<org.opennms.netmgt.config.linkd.Package, List<String>> m_pkgIpMap = new HashMap<org.opennms.netmgt.config.linkd.Package, List<String>>();

	/**
	 * The HashMap that associates the OIDS masks to class name for Vlans
	 */
	private static Map<String,String> m_oidMask2VlanclassName = new HashMap<String,String>();

	/**
	 * The HashMap that associates the OIDS masks to class name for IpRoutes
	 */
	 private static Map<String,String> m_oidMask2IpRouteclassName = new HashMap<String,String>();
	 
	/**
	 * <p>Constructor for LinkdConfigManager.</p>
	 *
	 * @author <a href="mailto:david@opennms.org">David Hustace</a>
     * @author <a href="mailto:antonio@opennms.org">Antonio Russo</a>
	 * @param reader a {@link java.io.Reader} object.
	 * @throws org.exolab.castor.xml.MarshalException if any.
	 * @throws org.exolab.castor.xml.ValidationException if any.
	 * @throws java.io.IOException if any.
	 */
	@Deprecated
    public LinkdConfigManager(final Reader reader) throws MarshalException, ValidationException, IOException {
        reloadXML(reader);
    }

    /**
     * <p>Constructor for LinkdConfigManager.</p>
     *
     * @param stream a {@link java.io.InputStream} object.
     * @throws org.exolab.castor.xml.MarshalException if any.
     * @throws org.exolab.castor.xml.ValidationException if any.
     * @throws java.io.IOException if any.
     */
    public LinkdConfigManager(final InputStream stream) throws MarshalException, ValidationException, IOException {
        reloadXML(stream);
    }

    public Lock getReadLock() {
        return m_readLock;
    }
    
    public Lock getWriteLock() {
        return m_writeLock;
    }

    /**
     * Whether autodiscovery is enabled in linkd-config (default: false)
     */
    public boolean isAutoDiscoveryEnabled() {
        getReadLock().lock();
        try {
            if (m_config.hasAutoDiscovery()) return m_config.getAutoDiscovery();
        } finally {
            getReadLock().unlock();
        }
        return false;
    }

    /**
     * Whether vlan discovery is enabled in linkd-config (default: true)
     */
    public boolean isVlanDiscoveryEnabled() {
        getReadLock().lock();
        try {
            
        } finally {
            getReadLock().unlock();
        }
        return true;
    }

    /**
     * This method is used to determine if the named interface is included in
     * the passed package definition. If the interface belongs to the package
     * then a value of true is returned. If the interface does not belong to the
     * package a false value is returned.
     *
     * <strong>Note: </strong>Evaluation of the interface against a package
     * filter will only work if the IP is already in the database.
     *
     * @param iface
     *            The interface to test against the package.
     * @param pkg
     *            The package to check for the inclusion of the interface.
     * @return True if the interface is included in the package, false
     *         otherwise.
     */
    public boolean isInterfaceInPackage(final String iface, final org.opennms.netmgt.config.linkd.Package pkg) {
        boolean filterPassed = false;
    
        getReadLock().lock();
        
        try {
            // get list of IPs in this package
            final List<String> ipList = m_pkgIpMap.get(pkg);
            if (ipList != null && ipList.size() > 0) {
                filterPassed = ipList.contains(iface);
            }
        
            LogUtils.debugf(this, "interfaceInPackage: Interface %s passed filter for package %s?: %s", iface, pkg.getName(), (filterPassed? "True":"False"));
        
            if (!filterPassed) return false;
    
            return isInterfaceInPackageRange(iface, pkg);
        } finally {
            getReadLock().unlock();
        }
    }
    
    public boolean isInterfaceInPackageRange(final String iface, final org.opennms.netmgt.config.linkd.Package pkg) {
        if (pkg == null) return false;

        //
        // Ensure that the interface is in the specific list or
        // that it is in the include range and is not excluded
        //
        boolean has_specific = false;
        boolean has_range_include = false;
        boolean has_range_exclude = false;
 
        getReadLock().lock();
        try {
            long addr = InetAddressUtils.toIpAddrLong(iface);
    
            // if there are NO include ranges then treat act as if the user include
            // the range 0.0.0.0 - 255.255.255.255
            has_range_include = pkg.getIncludeRangeCount() == 0 && pkg.getSpecificCount() == 0;
    
            // Specific wins; if we find one, return immediately.
            for (final String spec : pkg.getSpecificCollection()) {
                final long speca = InetAddressUtils.toIpAddrLong(spec);
                if (speca == addr) {
                    has_specific = true;
                    break;
                }
            }
            if (has_specific) return true;
    
            for (final String url : pkg.getIncludeUrlCollection()) {
                has_specific = isInterfaceInUrl(iface, url);
                if (has_specific) break;
            }
            if (has_specific) return true;
    
            if (!has_range_include) {
                for (final IncludeRange rng : pkg.getIncludeRangeCollection()) {
                    final long start = InetAddressUtils.toIpAddrLong(rng.getBegin());
                    if (addr > start) {
                        final long end = InetAddressUtils.toIpAddrLong(rng.getEnd());
                        if (addr <= end) {
                            has_range_include = true;
                            break;
                        }
                    } else if (addr == start) {
                        has_range_include = true;
                        break;
                    }
                }
            }
    
            for (final ExcludeRange rng : pkg.getExcludeRangeCollection()) {
                long start = InetAddressUtils.toIpAddrLong(rng.getBegin());
                if (addr > start) {
                    long end = InetAddressUtils.toIpAddrLong(rng.getEnd());
                    if (addr <= end) {
                        has_range_exclude = true;
                        break;
                    }
                } else if (addr == start) {
                    has_range_exclude = true;
                    break;
                }
            }
    
            return has_range_include && !has_range_exclude;
        } finally {
            getReadLock().unlock();
        }
    }

    /**
     * <p>enumeratePackage</p>
     *
     * @return a {@link java.util.Enumeration} object.
     */
    public Enumeration<Package> enumeratePackage() {
        getReadLock().lock();
        try {
            return getConfiguration().enumeratePackage();
        } finally {
            getReadLock().unlock();
        }
    }

    /**
     * Return the linkd configuration object.
     *
     * @return a {@link org.opennms.netmgt.config.linkd.LinkdConfiguration} object.
     */
    public LinkdConfiguration getConfiguration() {
        getReadLock().lock();
        try {
            return m_config;
        } finally {
            getReadLock().unlock();
        }
    }

	/** {@inheritDoc} */
	public org.opennms.netmgt.config.linkd.Package getPackage(final String name) {
	    getReadLock().lock();
	    try {
            for (final org.opennms.netmgt.config.linkd.Package thisPackage : m_config.getPackageCollection()) {
                final String n = thisPackage.getName();
                if (n != null && n.equals(name)) {
                    return thisPackage;
                }
            }
	    } finally {
	        getReadLock().unlock();
	    }
        return null;
    }
    
    /** {@inheritDoc} */
    public List<String> getIpList(final Package pkg) {
        getReadLock().lock();
        
        try {
            if (pkg == null) return null;
    
            final Filter filter = pkg.getFilter();
            if (filter == null) return null;
    
            final StringBuffer filterRules = new StringBuffer(filter.getContent());
    
            LogUtils.debugf(this, "createPackageIpMap: package is %s. filter rules are: %s", pkg.getName(), filterRules.toString());
            return FilterDaoFactory.getInstance().getIPList(filterRules.toString());
        } finally {
            getReadLock().unlock();
        }
    }

    /** {@inheritDoc} */
    public String getIpRouteClassName(final String sysoid) {
        getReadLock().lock();
        try {
            for (final String oidMask : m_oidMask2IpRouteclassName.keySet()) {
                if (sysoid.startsWith(oidMask)) {
                    return m_oidMask2IpRouteclassName.get(oidMask);
                }
            }
        } finally {
            getReadLock().unlock();
        }
        return DEFAULT_IP_ROUTE_CLASS_NAME;
    }

    /** {@inheritDoc} */
    public String getVlanClassName(final String sysoid) {
        getReadLock().lock();
        try {
            for (final String oidMask : m_oidMask2VlanclassName.keySet()) {
                if (sysoid.startsWith(oidMask)) {
                    return m_oidMask2VlanclassName.get(oidMask);
                }
            }
        } finally {
            getReadLock().unlock();
        }
        return null;
    }

    /** {@inheritDoc} */
    public List<SnmpCollection> getSnmpCollections(final String ipaddr, final String sysoid) {
        List<SnmpCollection> snmpcolls = new ArrayList<SnmpCollection>();

        getReadLock().lock();
        try {
            Iterator<String> ite = getAllPackageMatches(ipaddr).iterator();
            
            while (ite.hasNext()) {
                final String pkgName = ite.next();
                snmpcolls.add(getSnmpCollection(ipaddr, sysoid, pkgName));
            }
        } finally {
            getReadLock().unlock();
        }
        return snmpcolls;
    }

    /** {@inheritDoc} */
    public SnmpCollection getSnmpCollection(final String ipaddr, final String sysoid, final String pkgName) {
        getReadLock().lock();
        try {
            final Package pkg = getPackage(pkgName);
            if (pkg != null) {
                final SnmpCollection collection = getCollectionForIP(ipaddr);
                populateSnmpCollection(collection, pkg, sysoid);
                return collection;
            }
        } finally {
            getReadLock().unlock();
        }
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * Returns the first package that the IP belongs to, null if none.
     *
     * <strong>Note: </strong>Evaluation of the interface against a package
     * filter will only work if the IP is already in the database.
     */
    public org.opennms.netmgt.config.linkd.Package getFirstPackageMatch(final String ipaddr) {
        getReadLock().lock();
        try {
            for (final org.opennms.netmgt.config.linkd.Package pkg : m_config.getPackageCollection()) {
                if (isInterfaceInPackage(ipaddr, pkg)) {
                    return pkg;
                }
            }
        } finally {
            getReadLock().unlock();
        }
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * Returns a list of package names that the IP belongs to, null if none.
     *
     * <strong>Note: </strong>Evaluation of the interface against a package
     * filter will only work if the IP is already in the database.
     */
    public List<String> getAllPackageMatches(final String ipaddr) {
        final List<String> matchingPkgs = new ArrayList<String>();
        
        getReadLock().lock();
        try {
            for (final org.opennms.netmgt.config.linkd.Package pkg : m_config.getPackageCollection()) {
                final String pkgName = pkg.getName();
                if (isInterfaceInPackage(ipaddr, pkg)) {
                    matchingPkgs.add(pkgName);
                }
            }
        } finally {
            getReadLock().unlock();
        }
        return matchingPkgs;
    }

    /** {@inheritDoc} */
    public DiscoveryLink getDiscoveryLink(final String pkgName) {
        getReadLock().lock();

        try {
            final Package pkg = getPackage(pkgName);
    
            if (pkg == null) return null;

            boolean forceIpRouteDiscoveryOnEthernet = false;
            if (m_config.hasForceIpRouteDiscoveryOnEthernet()) forceIpRouteDiscoveryOnEthernet = m_config.getForceIpRouteDiscoveryOnEthernet();

            final DiscoveryLink discoveryLink = new DiscoveryLink();
            discoveryLink.setPackageName(pkg.getName());
            discoveryLink.setInitialSleepTime(getInitialSleepTime());
    
            discoveryLink.setSnmpPollInterval(pkg.hasSnmp_poll_interval()? pkg.getSnmp_poll_interval() : getSnmpPollInterval());
            discoveryLink.setDiscoveryInterval(pkg.hasDiscovery_link_interval()? pkg.getDiscovery_link_interval() : getDiscoveryLinkInterval());
            discoveryLink.setDiscoveryUsingBridge(pkg.hasUseBridgeDiscovery()? pkg.getUseBridgeDiscovery() : useBridgeDiscovery());
            discoveryLink.setDiscoveryUsingCdp(pkg.hasUseCdpDiscovery()? pkg.getUseCdpDiscovery() : useCdpDiscovery());
            discoveryLink.setDiscoveryUsingRoutes(pkg.hasUseIpRouteDiscovery()? pkg.getUseIpRouteDiscovery() : useIpRouteDiscovery());
            discoveryLink.setEnableDownloadDiscovery(pkg.hasEnableDiscoveryDownload()? pkg.getEnableDiscoveryDownload() : enableDiscoveryDownload());
            discoveryLink.setForceIpRouteDiscoveryOnEtherNet(pkg.hasForceIpRouteDiscoveryOnEthernet()? pkg.getForceIpRouteDiscoveryOnEthernet() : forceIpRouteDiscoveryOnEthernet);
    
            return discoveryLink;
        } finally {
            getReadLock().unlock();
        }
    }
    
    /** {@inheritDoc} */
    public boolean hasClassName(final String sysoid) {
        getReadLock().lock();
        try {
            for (final String oidMask : m_oidMask2VlanclassName.keySet()) {
                if (sysoid.startsWith(oidMask)) {
                    return true;
                }
            }
        } finally {
            getReadLock().unlock();
        }
        return false;
    }

    /**
     * <p>update</p>
     *
     * @throws java.io.IOException if any.
     * @throws org.exolab.castor.xml.MarshalException if any.
     * @throws org.exolab.castor.xml.ValidationException if any.
     */
    public abstract void update() throws IOException, MarshalException, ValidationException;

    /**
     * This method is used to establish package against IP list mapping, with
     * which, the IP list is selected per package via the configured filter rules
     * from the database.
     */
    public void updatePackageIpListMap() {
        getWriteLock().lock();
        try {
            for (final org.opennms.netmgt.config.linkd.Package pkg : m_config.getPackageCollection()) {
                //
                // Get a list of IP addresses per package against the filter rules from
                // database and populate the package, IP list map.
                //
                try {
                    final List<String> ipList = getIpList(pkg);
                    LogUtils.tracef(this, "createPackageIpMap: package %s: ipList size = %d", pkg.getName(), ipList.size());
    
                    if (ipList != null && ipList.size() > 0) {
                        m_pkgIpMap.put(pkg, ipList);
                    }
                } catch (final Throwable t) {
                    LogUtils.errorf(this, t, "createPackageIpMap: failed to map package: %s to an IP list", pkg.getName());
                }
            }
        } finally {
            getWriteLock().unlock();
        }
    }

    /**
     * <p>useIpRouteDiscovery</p>
     *
     * @return a boolean.
     */
    protected boolean useIpRouteDiscovery() {
        if (m_config.hasUseIpRouteDiscovery()) return m_config.getUseIpRouteDiscovery();
        return true;
    }

    /**
     * <p>saveRouteTable</p>
     *
     * @return a boolean.
     */
    protected boolean saveRouteTable() {
        if (m_config.hasSaveRouteTable()) return m_config.getSaveRouteTable();
        return true;
    }

    /**
     * <p>useCdpDiscovery</p>
     *
     * @return a boolean.
     */
    protected boolean useCdpDiscovery() {
        if (m_config.hasUseCdpDiscovery()) return m_config.getUseCdpDiscovery();
        return true;
    }
    
    /**
     * <p>useBridgeDiscovery</p>
     *
     * @return a boolean.
     */
    protected boolean useBridgeDiscovery() {
        if (m_config.hasUseBridgeDiscovery()) return m_config.getUseBridgeDiscovery();
        return true;
    }

    /**
     * <p>saveStpNodeTable</p>
     *
     * @return a boolean.
     */
    protected boolean saveStpNodeTable() {
        if (m_config.hasSaveStpNodeTable()) return m_config.getSaveStpNodeTable();
        return true;
    }
    
    /**
     * <p>enableDiscoveryDownload</p>
     *
     * @return a boolean.
     */
    protected boolean enableDiscoveryDownload() {
        if (m_config.hasEnableDiscoveryDownload()) return m_config.getEnableDiscoveryDownload();
        return false;
    }   
    
    /**
     * <p>saveStpInterfaceTable</p>
     *
     * @return a boolean.
     */
    protected boolean saveStpInterfaceTable() {
        if (m_config.hasSaveStpInterfaceTable()) return m_config.getSaveStpInterfaceTable();
        return true;
    }

    protected long getInitialSleepTime() {
        if (m_config.hasInitial_sleep_time()) return m_config.getInitial_sleep_time();
        return 1800000;
    }

    protected long getSnmpPollInterval() {
        if (m_config.hasSnmp_poll_interval()) return m_config.getSnmp_poll_interval();
        return 900000;
    }

    protected long getDiscoveryLinkInterval() {
        if (m_config.hasSnmp_poll_interval()) return m_config.getDiscovery_link_interval();
        return 3600000;
    }

    /**
     * <p>getThreads</p>
     *
     * @return a int.
     */
    protected int getThreads() {
        if (m_config.hasThreads()) return m_config.getThreads();
        return 5;
    }

    /** {@inheritDoc} */
    protected boolean hasIpRouteClassName(final String sysoid) {
        for (final String oidMask : m_oidMask2IpRouteclassName.keySet()) {
            if (sysoid.startsWith(oidMask)) {
                return true;
            }
        }
        return false;
    }
    
    private void updateUrlIpMap() {
        for (final org.opennms.netmgt.config.linkd.Package pkg : m_config.getPackageCollection()) {
            if (pkg == null) continue;
            for (final String urlname : pkg.getIncludeUrlCollection()) {
                final java.util.List<String> iplist = IpListFromUrl.parse(urlname);
                if (iplist.size() > 0) {
                    m_urlIPMap.put(urlname, iplist);
                }
            }
        }
    }

    private void initializeIpRouteClassNames() throws IOException, MarshalException, ValidationException {
        getWriteLock().lock();
        try {
            final Iproutes iproutes = m_config.getIproutes();
            if (iproutes == null) {
                LogUtils.infof(this, "no iproutes found in config");
                return;
            }
    
            for (final Vendor vendor : iproutes.getVendorCollection()) {
                final SnmpObjectId oidMask = new SnmpObjectId(vendor.getSysoidRootMask());
                final String curClassName = vendor.getClassName();
                m_oidMask2IpRouteclassName.put(oidMask.toString(), curClassName);
                LogUtils.debugf(this, "getIpRouteClassNames:  adding class %s for oid %s", curClassName, oidMask.toString());
            }
        } finally {
            getWriteLock().unlock();
        }
    }

	private void initializeVlanClassNames() throws IOException, MarshalException, ValidationException {
	    getWriteLock().lock();
	    try {
    		final Vlans vlans = m_config.getVlans();
    		if (vlans == null) {
    		    LogUtils.infof(this, "no vlans found in config");
    		}
    
            final List<String> excludedOids = new ArrayList<String>();
    		for (final Vendor vendor : vlans.getVendorCollection()) {
    		    final SnmpObjectId curRootSysOid = new SnmpObjectId(vendor.getSysoidRootMask());
    		    final String curClassName = vendor.getClassName();
    
    			for (final String specific : vendor.getSpecific()) {
    			    final SnmpObjectId oidMask = new SnmpObjectId(specific);
    				oidMask.prepend(curRootSysOid);
    				m_oidMask2VlanclassName.put(oidMask.toString(), curClassName);
    				LogUtils.debugf(this, "getClassNames:  adding class %s for oid %s", curClassName, oidMask.toString());
    			}
    
    			for (final ExcludeRange excludeRange : vendor.getExcludeRangeCollection()) {
    			    final SnmpObjectId snmpBeginOid = new SnmpObjectId(excludeRange.getBegin());
    			    final SnmpObjectId snmpEndOid = new SnmpObjectId(excludeRange.getEnd());
    				final SnmpObjectId snmpRootOid = getRootOid(snmpBeginOid);
    				if (snmpBeginOid.getLength() == snmpEndOid.getLength() && snmpRootOid.isRootOf(snmpEndOid)) {
    				    final SnmpObjectId snmpCurOid = new SnmpObjectId(snmpBeginOid);
    					while (snmpCurOid.compare(snmpEndOid) <= 0) {
    						excludedOids.add(snmpCurOid.toString());
    						LogUtils.debugf(this, "getClassNames:  signing excluded class %s for oid %s", curClassName, curRootSysOid.toString().concat(snmpCurOid.toString()));
    						int lastCurCipher = snmpCurOid.getLastIdentifier();
    						lastCurCipher++;
    						int[] identifiers = snmpCurOid.getIdentifiers();
    						identifiers[identifiers.length - 1] = lastCurCipher;
    						snmpCurOid.setIdentifiers(identifiers);
    					}
    				}
    			}
    
    			for (final IncludeRange includeRange : vendor.getIncludeRangeCollection()) {
    			    final SnmpObjectId snmpBeginOid = new SnmpObjectId(includeRange.getBegin());
    				final SnmpObjectId snmpEndOid = new SnmpObjectId(includeRange.getEnd());
    				final SnmpObjectId rootOid = getRootOid(snmpBeginOid);
    				if (snmpBeginOid.getLength() == snmpEndOid.getLength() && rootOid.isRootOf(snmpEndOid)) {
    					final SnmpObjectId snmpCurOid = new SnmpObjectId(snmpBeginOid);
    					while (snmpCurOid.compare(snmpEndOid) <= 0) {
    						if (!excludedOids.contains(snmpBeginOid.toString())) {
    							final SnmpObjectId oidMask = new SnmpObjectId(snmpBeginOid);
    							oidMask.prepend(curRootSysOid);
    							m_oidMask2VlanclassName.put(oidMask.toString(), curClassName);
    							LogUtils.debugf(this, "getClassNames:  adding class %s for oid %s", curClassName, oidMask.toString());
    						}
    						int lastCipher = snmpBeginOid.getLastIdentifier();
    						lastCipher++;
    						int[] identifiers = snmpBeginOid.getIdentifiers();
    						identifiers[identifiers.length - 1] = lastCipher;
    						snmpCurOid.setIdentifiers(identifiers);
    					}
    				}
    			}
    		}
	    } finally {
	        getWriteLock().unlock();
	    }
	}

    private void populateSnmpCollection(final SnmpCollection coll, final Package pkg, final String sysoid) {
        coll.setPackageName(pkg.getName());
        coll.setInitialSleepTime(getInitialSleepTime());
        coll.setPollInterval(pkg.hasSnmp_poll_interval()? pkg.getSnmp_poll_interval() : getSnmpPollInterval());
        
        if (hasIpRouteClassName(sysoid)) {
            coll.setIpRouteClass(getIpRouteClassName(sysoid));
            LogUtils.debugf(this, "populateSnmpCollection: found class to get ipRoute: %s", coll.getIpRouteClass());
        } else {
            coll.setIpRouteClass(DEFAULT_IP_ROUTE_CLASS_NAME);
            LogUtils.debugf(this, "populateSnmpCollection: Using default class to get ipRoute: %s", coll.getIpRouteClass());
        }
        
        if (pkg.hasEnableVlanDiscovery() && pkg.getEnableVlanDiscovery() && hasClassName(sysoid)) {
            coll.setVlanClass(getVlanClassName(sysoid));
            LogUtils.debugf(this, "populateSnmpCollection: found class to get Vlans: %s", coll.getVlanClass());
        } else if (!pkg.hasEnableVlanDiscovery() && isVlanDiscoveryEnabled() && hasClassName(sysoid)) {
            coll.setVlanClass(getVlanClassName(sysoid));
            LogUtils.debugf(this, "populateSnmpCollection: found class to get Vlans: %s", coll.getVlanClass());
        } else {
            LogUtils.debugf(this, "populateSnmpCollection: no class found to get Vlans or VlanDiscoveryDisabled for Package: %s", pkg.getName());
        }

        coll.collectCdpTable(pkg.hasUseCdpDiscovery()? pkg.getUseCdpDiscovery() : useCdpDiscovery());

        final boolean useIpRouteDiscovery = (pkg.hasUseIpRouteDiscovery()? pkg.getUseIpRouteDiscovery() : useIpRouteDiscovery());
        final boolean saveRouteTable = (pkg.hasSaveRouteTable()? pkg.getSaveRouteTable() : saveRouteTable());

        coll.SaveIpRouteTable(saveRouteTable);
        coll.collectIpRouteTable(useIpRouteDiscovery || saveRouteTable);

        final boolean useBridgeDiscovery = (pkg.hasUseBridgeDiscovery()? pkg.getUseBridgeDiscovery() : useBridgeDiscovery());
        coll.collectBridgeForwardingTable(useBridgeDiscovery);

        final boolean saveStpNodeTable = (pkg.hasSaveStpNodeTable()? pkg.getSaveStpNodeTable() : saveStpNodeTable());

        coll.saveStpNodeTable(saveStpNodeTable);
        coll.collectStpNode(useBridgeDiscovery || saveStpNodeTable);

        final boolean saveStpInterfaceTable = (pkg.hasSaveStpInterfaceTable()? pkg.getSaveStpInterfaceTable() : saveStpInterfaceTable());
        
        coll.saveStpInterfaceTable(saveStpInterfaceTable);
        coll.collectStpTable(useBridgeDiscovery || saveStpInterfaceTable);
    }
    
    
	private SnmpObjectId getRootOid(final SnmpObjectId snmpObj) {
        getReadLock().lock();
        try {
    	    final int[] identifiers = snmpObj.getIdentifiers();
    		final int[] rootIdentifiers = new int[identifiers.length - 1];
    		for (int i = 0; i < identifiers.length - 1; i++) {
    			rootIdentifiers[i] = identifiers[i];
    		}
    		return new SnmpObjectId(rootIdentifiers);
        } finally {
            getReadLock().unlock();
        }
	}
	
    private SnmpCollection getCollectionForIP(final String ipaddr) {
        SnmpCollection coll = null;
        try {
            coll = new SnmpCollection(SnmpPeerFactory.getInstance().getAgentConfig(InetAddress.getByName(ipaddr)));
        } catch (final Throwable t) {
            LogUtils.errorf(this, t, "getSnmpCollection: Failed to load snmpcollection parameter from snmp configuration file");
        }

        return coll;
    }

    /**
     * This method is used to determine if the named interface is included in
     * the passed package's URL includes. If the interface is found in any of
     * the URL files, then a value of true is returned, else a false value is
     * returned.
     * 
     * <pre>
     * 
     *  The file URL is read and each entry in this file checked. Each line
     *   in the URL file can be one of -
     *   &lt;IP&gt;&lt;space&gt;#&lt;comments&gt;
     *   or
     *   &lt;IP&gt;
     *   or
     *   #&lt;comments&gt;
     *  
     *   Lines starting with a '#' are ignored and so are characters after
     *   a '&lt;space&gt;#' in a line.
     *  
     * </pre>
     * 
     * @param addr
     *            The interface to test against the package's URL
     * @param url
     *            The URL file to read
     * 
     * @return True if the interface is included in the URL, false otherwise.
     */
    private boolean isInterfaceInUrl(final String addr, final String url) {
        getReadLock().lock();

        try {
            // get list of IPs in this URL
            final List<String> iplist = m_urlIPMap.get(url);
            if (iplist != null && iplist.size() > 0) {
                return iplist.contains(addr);
            }
        } finally {
            getReadLock().unlock();
        }
        return false;
    }
    
    /**
     * <p>reloadXML</p>
     *
     * @param reader a {@link java.io.Reader} object.
     * @throws org.exolab.castor.xml.MarshalException if any.
     * @throws org.exolab.castor.xml.ValidationException if any.
     * @throws java.io.IOException if any.
     */
    @Deprecated
    protected void reloadXML(final Reader reader) throws MarshalException, ValidationException, IOException {
        getWriteLock().lock();
        try {
            m_config = CastorUtils.unmarshal(LinkdConfiguration.class, reader);
            updateUrlIpMap();
            updatePackageIpListMap();
            initializeVlanClassNames();
            initializeIpRouteClassNames();
        } finally {
            getWriteLock().unlock();
        }
    }

    /**
     * <p>reloadXML</p>
     *
     * @param stream a {@link java.io.InputStream} object.
     * @throws org.exolab.castor.xml.MarshalException if any.
     * @throws org.exolab.castor.xml.ValidationException if any.
     * @throws java.io.IOException if any.
     */
    protected void reloadXML(final InputStream stream) throws MarshalException, ValidationException, IOException {
        getWriteLock().lock();
        try {
            m_config = CastorUtils.unmarshal(LinkdConfiguration.class, stream, CastorUtils.PRESERVE_WHITESPACE);
            updateUrlIpMap();
            updatePackageIpListMap();
            initializeVlanClassNames();
            initializeIpRouteClassNames();
        } finally {
            getWriteLock().unlock();
        }
    }
    /**
     * Saves the current in-memory configuration to disk and reloads
     *
     * @throws org.exolab.castor.xml.MarshalException if any.
     * @throws java.io.IOException if any.
     * @throws org.exolab.castor.xml.ValidationException if any.
     */
    public void save() throws MarshalException, IOException, ValidationException {
        getWriteLock().lock();
        
        try {
            // marshall to a string first, then write the string to the file. This
            // way the original config isn't lost if the xml from the marshall is hosed.
            final StringWriter stringWriter = new StringWriter();
            Marshaller.marshal(m_config, stringWriter);
            saveXml(stringWriter.toString());
        
            update();
        } finally {
            getWriteLock().unlock();
        }
    }

    /**
     * <p>saveXml</p>
     *
     * @param xml a {@link java.lang.String} object.
     * @throws java.io.IOException if any.
     */
    protected abstract void saveXml(final String xml) throws IOException;
}
