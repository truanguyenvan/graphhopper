/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.routing.util;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.DecimalEncodedValueImpl;
import com.graphhopper.routing.ev.EncodedValue;
import com.graphhopper.storage.IntsRef;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.Helper;
import com.graphhopper.util.PMap;
import com.graphhopper.util.PointList;

import static com.graphhopper.util.Helper.keepIn;

import java.util.*;


public class WaterbusFlagEncoder extends AbstractFlagEncoder {
    protected static final int PUSHING_SECTION_SPEED = 4;

	protected final Map<String, Integer> trackTypeSpeedMap = new HashMap<>();
    protected final Set<String> badSurfaceSpeedMap = new HashSet<>();
    private boolean speedTwoDirections = true;
    // This value determines the maximal possible on roads with bad surfaces
    protected int badSurfaceSpeed;

    /**
     * A map which associates string to speed. Get some impression:
     * http://www.itoworld.com/map/124#fullscreen
     * http://wiki.openstreetmap.org/wiki/OSM_tags_for_routing/Maxspeed
     */
    protected final Map<String, Integer> defaultSpeedMap = new HashMap<>();

    public WaterbusFlagEncoder() {
        this(5, 5, 0);
    }
    public WaterbusFlagEncoder(int speedBits, double speedFactor, int maxTurnCosts) {
        this(speedBits, speedFactor, maxTurnCosts, false);
    }

    public WaterbusFlagEncoder(int speedBits, double speedFactor, int maxTurnCosts, boolean speedTwoDirections) {
        this(new PMap().putObject("speed_bits", speedBits).putObject("speed_factor", speedFactor).
        putObject("max_turn_costs", maxTurnCosts).putObject("speed_two_directions", speedTwoDirections));
    }

    public WaterbusFlagEncoder(PMap properties) {
        super(properties.getInt("speed_bits", 5),
                properties.getDouble("speed_factor", 5),
                properties.getInt("max_turn_costs", properties.getBool("turn_costs", false) ? 1 : 0));
        

        setSpeedTwoDirections(properties.getBool("speed_two_directions", true));
        String prefix = getName();
        avgSpeedEnc = new DecimalEncodedValueImpl(EncodingManager.getKey(prefix, "average_speed"), speedBits, speedFactor, speedTwoDirections);
        
        intendedValues.add("waterbus");
        
        defaultSpeedMap.put("waterbus",60);
        trackTypeSpeedMap.put(null, 30);

        // limit speed on bad surfaces to 30 km/h
        badSurfaceSpeed = 10;
        maxPossibleSpeed = 60;
    }

    public WaterbusFlagEncoder setSpeedTwoDirections(boolean value) {
        speedTwoDirections = value;
        return this;
    }

    public TransportationMode getTransportationMode() {
        return TransportationMode.WATERBUS;
    }

    public int getVersion() {
        return 2;
    }

    /**
     * @param way   needed to retrieve tags
     * @param speed speed guessed e.g. from the road type or other tags
     * @return The assumed speed.
     */
    protected double applyMaxSpeed(ReaderWay way, double speed) {
        double maxSpeed = getMaxSpeed(way);
        // We obey speed limits
        if (isValidSpeed(maxSpeed)) {
            // We assume that the average speed is 90% of the allowed maximum
            return maxSpeed * 0.9;
        }
        return speed;
    }
    /**
     * Define the place of the speedBits in the edge flags for car.
     */
    @Override
    public void createEncodedValues(List<EncodedValue> registerNewEncodedValue) {
        // first two bits are reserved for route handling in superclass
        super.createEncodedValues(registerNewEncodedValue);
        registerNewEncodedValue.add(avgSpeedEnc);
    }

    protected double getSpeed(ReaderWay way) {
    	String highwayValue = way.getTag("highway");
    	Integer speed = defaultSpeedMap.get(highwayValue);
        return speed;
    }

    @Override
    public EncodingManager.Access getAccess(ReaderWay way) {
        // TODO: Ferries have conditionals, like opening hours or are closed during some time in the year
        String highwayValue = way.getTag("highway");

        if (!defaultSpeedMap.containsKey(highwayValue))
            return EncodingManager.Access.CAN_SKIP;
        
        return EncodingManager.Access.WAY;
    }

    @Override
    public IntsRef handleWayTags(IntsRef edgeFlags, ReaderWay way) {
        EncodingManager.Access access = getAccess(way);

        if (access.canSkip()) {
            return edgeFlags;
        }

        if (!access.isFerry()) {
            // get assumed speed from highway type
            double speed = getSpeed(way);
            speed = applyMaxSpeed(way, speed);
            speed = applyBadSurfaceSpeed(way, speed);
            setSpeed(false, edgeFlags, speed);
            if (speedTwoDirections)
                setSpeed(true, edgeFlags, speed);
            accessEnc.setBool(false, edgeFlags, true);
            accessEnc.setBool(true, edgeFlags, true);
        } else {
            double ferrySpeed = ferrySpeedCalc.getSpeed(way);
            accessEnc.setBool(false, edgeFlags, true);
            accessEnc.setBool(true, edgeFlags, true);
            setSpeed(false, edgeFlags, ferrySpeed);
            if (speedTwoDirections)
                setSpeed(true, edgeFlags, ferrySpeed);
        }

        return edgeFlags;
    }
    
    @Override
    public void applyWayTags(ReaderWay way, EdgeIteratorState edge) {
    	PointList pl = edge.fetchWayGeometry(FetchMode.ALL);
//        if (!pl.is3D())
//            throw new IllegalStateException(toString() + " requires elevation data to improve speed calculation based on it. Please enable it in config via e.g. graph.elevation.provider: srtm");

        IntsRef intsRef = edge.getFlags();
        if (way.hasTag("highway", "steps"))
            // do not change speed
            // note: although tunnel can have a difference in elevation it is very unlikely that the elevation data is correct for a tunnel
            return;

        // Decrease the speed for ele increase (incline), and decrease the speed for ele decrease (decline). The speed-decrease
        // has to be bigger (compared to the speed-increase) for the same elevation difference to simulate loosing energy and avoiding hills.
        // For the reverse speed this has to be the opposite but again keeping in mind that up+down difference.
        double incEleSum = 0, incDist2DSum = 0, decEleSum = 0, decDist2DSum = 0;
        // double prevLat = pl.getLatitude(0), prevLon = pl.getLongitude(0);
        double prevEle = pl.getEle(0);
        double fullDist2D = edge.getDistance();

        // for short edges an incline makes no sense and for 0 distances could lead to NaN values for speed, see #432
        if (fullDist2D < 2)
            return;

        double eleDelta = pl.getEle(pl.size() - 1) - prevEle;
        if (eleDelta > 0.1) {
            incEleSum = eleDelta;
            incDist2DSum = fullDist2D;
        } else if (eleDelta < -0.1) {
            decEleSum = -eleDelta;
            decDist2DSum = fullDist2D;
        }

        // Calculate slop via tan(asin(height/distance)) but for rather smallish angles where we can assume tan a=a and sin a=a.
        // Then calculate a factor which decreases or increases the speed.
        // Do this via a simple quadratic equation where y(0)=1 and y(0.3)=1/4 for incline and y(0.3)=2 for decline
        double fwdIncline = incDist2DSum > 1 ? incEleSum / incDist2DSum : 0;
        double fwdDecline = decDist2DSum > 1 ? decEleSum / decDist2DSum : 0;
        double restDist2D = fullDist2D - incDist2DSum - decDist2DSum;
        double maxSpeed = 40;
        if (accessEnc.getBool(false, intsRef)) {
            // use weighted mean so that longer incline influences speed more than shorter
            double speed = avgSpeedEnc.getDecimal(false, intsRef);
            double fwdFaster = 1 + 2 * keepIn(fwdDecline, 0, 0.2);
            fwdFaster = fwdFaster * fwdFaster;
            double fwdSlower = 1 - 5 * keepIn(fwdIncline, 0, 0.2);
            fwdSlower = fwdSlower * fwdSlower;
            speed = speed * (fwdSlower * incDist2DSum + fwdFaster * decDist2DSum + 1 * restDist2D) / fullDist2D;
            setSpeed(false, intsRef, keepIn(speed, PUSHING_SECTION_SPEED / 2.0, maxSpeed));
        }

        if (accessEnc.getBool(true, intsRef)) {
            double speedReverse = avgSpeedEnc.getDecimal(true, intsRef);
            double bwFaster = 1 + 2 * keepIn(fwdIncline, 0, 0.2);
            bwFaster = bwFaster * bwFaster;
            double bwSlower = 1 - 5 * keepIn(fwdDecline, 0, 0.2);
            bwSlower = bwSlower * bwSlower;
            speedReverse = speedReverse * (bwFaster * incDist2DSum + bwSlower * decDist2DSum + 1 * restDist2D) / fullDist2D;
            setSpeed(true, intsRef, keepIn(speedReverse, PUSHING_SECTION_SPEED / 2.0, maxSpeed));
        }
        edge.setFlags(intsRef);
    }

    /**
     * @param way   needed to retrieve tags
     * @param speed speed guessed e.g. from the road type or other tags
     * @return The assumed speed
     */
    protected double applyBadSurfaceSpeed(ReaderWay way, double speed) {
        // limit speed if bad surface
        if (badSurfaceSpeed > 0 && speed > badSurfaceSpeed && way.hasTag("surface", badSurfaceSpeedMap))
            speed = badSurfaceSpeed;
        return speed;
    }
    
    @Override
    public String getName() {
        return "waterbus";
    }
}
