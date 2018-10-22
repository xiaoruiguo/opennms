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

package org.opennms.core.time;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class PseudoClock {

    private static PseudoClock instance = new PseudoClock();

    private AtomicLong time = new AtomicLong();

    private PseudoClock() {
        reset();
    }

    public static PseudoClock getInstance() {
        return instance;
    }

    public void reset() {
        time.set(0);
    }

    public long getTime() {
        return time.get();
    }

    /**
     * Advances the clock time in the specified unit amount.
     *
     * @param amount the amount of units to advance in the clock
     * @param unit the used time unit
     * @return the current absolute timestamp
     */
    public long advanceTime(long amount, TimeUnit unit) {
        return time.addAndGet(unit.toMillis(amount));
    }
}
