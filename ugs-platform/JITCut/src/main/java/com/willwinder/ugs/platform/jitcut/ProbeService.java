/*
    Copyright 2017-2018 Will Winder

    This file is part of Universal Gcode Sender (UGS).

    UGS is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    UGS is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with UGS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.willwinder.ugs.platform.jitcut;

import com.google.common.base.Preconditions;
import com.willwinder.universalgcodesender.Utils;
import com.willwinder.universalgcodesender.gcode.util.GcodeUtils;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.listeners.UGSEventListener;
import com.willwinder.universalgcodesender.model.Axis;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.PartialPosition;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UGSEvent;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import com.willwinder.universalgcodesender.model.WorkCoordinateSystem;
import com.willwinder.universalgcodesender.model.events.ControllerStateEvent;
import com.willwinder.universalgcodesender.model.events.ProbeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

// xpaste | grep -o -P "(?<=command: ).*" > /tmp/asdf.gcode

/**
 * Methods that run various probe routines.
 *
 * @author wwinder
 */
public class ProbeService implements UGSEventListener {
    private static final Logger logger = Logger.getLogger(ProbeService.class.getName());
    private static final String WCS_PATTERN = "G10 L20 P%d %s";
    private static final double EPS = 0.000001; // Anything smaller than this may be assumed float error

    private final BackendAPI backend;
    private final Consumer<String> gcodeCallback; //SHAME Kinda bleh; probably oughtta be a tap in backend
    private final List<Position> probePositions = new ArrayList<>();
    private ProbeOperation currentOperation = ProbeOperation.NONE;
    private ProbeParameters params = null;
    private Continuation continuation = null;

    @FunctionalInterface
    private interface Continuation {
        void execute() throws Exception;
    }

    private enum ProbeOperation {
        NONE(0),
        Z(2),
        OUTSIDE_XY(4),
        OUTSIDE_XYZ(6),
        //INSIDE_XY    (4),
        //INSIDE_CIRCLE(4),
        //RAINY ANGLE(N),
        OUTSIDE_LINEAR_CENTER(4),
        INSIDE_LINEAR_CENTER(4),
        INSIDE_CIRCLE_CENTER(6),
        OUTSIDE_CIRCLE_CENTER(6),
        //RAINY INSIDE_CENTER(N),
        //RAINY PITCH(N),
        MEASURE_ANGLE(4),
        CYLINDER_SHELL(0),
        BOX_SOLID(0),
        MOVE_XP(0),
        LATHE_ROUND_FACE(0),
        LATHE_FLAT_FACE(0),
        LATHE_TAPER(0),
        ;

        private final int numProbes;

        ProbeOperation(int probes) {
            this.numProbes = probes;
        }

        /**
         * @return Expected number of probes.
         */
        public int getNumProbes() {
            return numProbes;
        }
    }

    /**
     * Parameters passed into the probe operations.
     */
    public static class ProbeParameters {
        public static enum BoxOrder {
            XYZ,
            XZY,
            YXZ,
            YZX,
            ZXY,
            ZYX
        }
        
        public String errorMessage;
        public UGSEvent event;
        public final double probeDiameter;
        public final double xSpacing;
        public final double ySpacing;
        public final double zSpacing;
        public final double xOffset;
        public final double yOffset;
        public final double zOffset;
        public final double xPush;
        public final double yPush;
        public final double zPush;
        public final double angle; //THINK This's existence implies incorrectly that all operations support it.  //RAINY Maybe they SHOULD? //THINK quaternion rather than xy angle only?
        public final double angleSpacing;
        public final double angleOtherSide;
        public final double cutDiameter;
        public final double cutLayerThicknessZ;
        public final double cutDepthZ;
        public final double cutSkipZ;
        public final double cutFeedRate; //THINK Separate parameter for Z/XY?
        public final boolean cutCCW;
        public final BoxOrder cutBoxOrder;
        public final double feedRate;
        public final double feedRateSlow;
        public final double retractAmount;
        public final WorkCoordinateSystem wcsToUpdate;
        public final Units units;

        // Results
        public final Position startPosition;
        public Position endPosition;
        private final Consumer<Object> callback; //SHAME Kinda bleh

        public ProbeParameters(double diameter, Position start,
                double xSpacing, double ySpacing, double zSpacing,
                double xOffset, double yOffset, double zOffset,
                double xPush, double yPush, double zPush,
                double angle, double angleSpacing, double angleOtherSide,
                double cutDiameter, double cutLayerThicknessZ, double cutDepthZ, double cutSkipZ, double cutFeedRate, boolean cutCCW, BoxOrder cutBoxOrder,
                double feedRate, double feedRateSlow, double retractAmount,
                Units u, WorkCoordinateSystem wcs,
                Consumer<Object> callback) {
            this.endPosition = null;
            this.probeDiameter = diameter;
            this.startPosition = start;
            this.xSpacing = xSpacing;
            this.ySpacing = ySpacing;
            this.zSpacing = zSpacing;
            this.xOffset = xOffset;
            this.yOffset = yOffset;
            this.zOffset = zOffset;
            this.xPush = xPush;
            this.yPush = yPush;
            this.zPush = zPush;
            this.angle = angle;
            this.angleSpacing = angleSpacing;
            this.angleOtherSide = angleOtherSide;
            this.cutDiameter = cutDiameter;
            this.cutLayerThicknessZ = cutLayerThicknessZ;
            this.cutDepthZ = cutDepthZ;
            this.cutSkipZ = cutSkipZ;
            this.cutFeedRate = cutFeedRate;
            this.cutCCW = cutCCW;
            this.cutBoxOrder = cutBoxOrder;
            this.feedRate = feedRate;
            this.feedRateSlow = feedRateSlow;
            this.retractAmount = retractAmount;
            this.units = u;
            this.wcsToUpdate = wcs;
            this.callback = callback;
        }
    }

    public ProbeService(BackendAPI backend, Consumer<String> gcodeCallback) {
        this.backend = backend;
        this.backend.addUGSEventListener(this);
        if (gcodeCallback == null) {
            gcodeCallback = (t) -> {
            };
        }
        this.gcodeCallback = gcodeCallback;
    }

    protected static double retractDistance(double spacing, double retractAmount) {
        return (spacing < 0) ? retractAmount : -1 * retractAmount;
    }

    private void resetProbe() {
        this.probePositions.clear();
        this.continuation = null;
        this.params = null;
        this.currentOperation = ProbeOperation.NONE;
    }

    public boolean probeCycleActive() {
        return this.currentOperation != ProbeOperation.NONE;
    }

    private void validateState() {
        if (!backend.isIdle()) {
            throw new IllegalStateException("Can only begin probing while IDLE.");
        }
    }

    void performZProbe(ProbeParameters params) throws IllegalStateException {
        validateState();
        currentOperation = ProbeOperation.Z;
        this.params = params;
        performZProbeInternal(0);
    }

    private void performZProbeInternal(int stepNumber) throws IllegalStateException {
        String unit = GcodeUtils.unitCommand(params.units);

        continuation = () -> performZProbeInternal(stepNumber + 1);
        try {
            switch (stepNumber) {
                case 0: {
                    // Reset (_, _, 0) to make it easier to retract.
                    updateWCS(params.wcsToUpdate, null, null, 0.0);

                    probe('Z', params.feedRate, params.zSpacing, params.units);
                    break;
                }
                case 1: {
                    gcode("G91 " + unit + " G0 Z" + retractDistance(params.zSpacing, params.retractAmount));
                    probe('Z', params.feedRateSlow, params.zSpacing, params.units);
                    break;
                }
                case 2: {
                    // Back to zero
                    String g0Abs = "G90 " + unit + " G0";
                    gcode(g0Abs + " Z0.0");
                    break;
                }
                case 3: {
                    // Once idle, perform calculations.
                    Preconditions.checkState(probePositions.size() == 2, "Unexpected number of probe positions.");
                    Position probe = probePositions.get(1).getPositionIn(params.units);

                    double zDir = Math.signum(params.zSpacing) * -1;
                    double zProbedOffset = zDir * params.zOffset; //DITTO

                    Position startPositionInUnits = params.startPosition.getPositionIn(params.units);
                    updateWCS(params.wcsToUpdate,
                            null,
                            null,
                            startPositionInUnits.z - probe.z + zProbedOffset);
                    break;
                }
                default:
                    throw new UnsupportedOperationException("Invalid step number: " + stepNumber);
            }
        } catch (Exception e) {
            resetProbe();
            logger.log(Level.SEVERE, "Exception during z probe operation.", e);
        }
    }

    void performOutsideCornerProbe(ProbeParameters params) throws IllegalStateException {
        validateState();
        currentOperation = ProbeOperation.OUTSIDE_XY;
        this.params = params;
        performOutsideCornerProbeInternal(0);
    }

    private void performOutsideCornerProbeInternal(int stepNumber) throws IllegalStateException {

        String g = GcodeUtils.unitCommand(params.units);
        String g0Abs = "G90 " + g + " G0";
        String g0Rel = "G91 " + g + " G0";

        continuation = () -> performOutsideCornerProbeInternal(stepNumber + 1);

        try {
            switch (stepNumber) {
                case 0: {
                    // Reset (0,0,_) to make it easier to retract.
                    updateWCS(params.wcsToUpdate, 0.0, 0.0, null);

                    gcode(g0Abs + " X" + params.xSpacing);
                    probe('Y', params.feedRate, params.ySpacing, params.units);
                    break;
                }
                case 1: {
                    gcode(g0Rel + " Y" + retractDistance(params.ySpacing, params.retractAmount));
                    probe('Y', params.feedRateSlow, params.ySpacing, params.units);
                    break;
                }
                case 2: {
                    gcode(g0Abs + " Y0.0");
                    gcode(g0Abs + " X0.0");
                    
                    Position probeY = probePositions.get(1).getPositionIn(params.units);
                    double yDir = Math.signum(params.ySpacing);
                    double extent = yDir*Math.min(yDir*(probeY.y + yDir*params.yPush), yDir*params.ySpacing);
                    //CHECK Ok, so...this went the wrong direction the two times I first tested it.
                    // - Then I added logging and ran it again...and it worked fine.
                    // - Something similar happened with the xyz - it nearly crashed the bit the first time.
                    // - Added logging, mysterious success.
                    // - AHA!  And I just tried xyz again, and it failed to descend enough - like it added
                    // -   an offset in the wrong direction or something.
                    // - And then I turned it off and on, and it worked.  Hmmmmm.
                    System.out.println("yDir " + yDir);
                    System.out.println("extent " + extent);
                    gcode(g0Abs + " Y" + extent);
                    
                    probe('X', params.feedRate, params.xSpacing, params.units);
                    break;
                }
                case 3: {
                    gcode(g0Rel + " X" + retractDistance(params.xSpacing, params.retractAmount));
                    probe('X', params.feedRateSlow, params.xSpacing, params.units);
                    break;
                }
                case 4: {
                    gcode(g0Abs + " X0.0");
                    gcode(g0Abs + " Y0.0");
                    break;
                }
                case 5: {
                    // Once idle, perform calculations.
                    Preconditions.checkState(probePositions.size() == 4, "Unexpected number of probe positions.");

                    Position probeY = probePositions.get(1).getPositionIn(params.units);
                    Position probeX = probePositions.get(3).getPositionIn(params.units);

                    double radius = params.probeDiameter / 2;
                    double xDir = Math.signum(params.xSpacing) * -1;
                    double yDir = Math.signum(params.ySpacing) * -1;
                    double xProbedOffset = xDir * (radius + params.xOffset); //DITTO
                    double yProbedOffset = yDir * (radius + params.yOffset); //DITTO

                    Position startPositionInUnits = params.startPosition.getPositionIn(params.units);
                    updateWCS(params.wcsToUpdate,
                            startPositionInUnits.x - probeX.x + xProbedOffset,
                            startPositionInUnits.y - probeY.y + yProbedOffset,
                            null);
                    break;
                }
                default:
                    throw new UnsupportedOperationException("Invalid step number: " + stepNumber);
            }
        } catch (Exception e) {
            resetProbe();
            logger.log(Level.SEVERE, "Exception during outside corner probe operation.", e);
        }
    }

    void performXYZProbe(ProbeParameters params) throws IllegalStateException {
        validateState();
        currentOperation = ProbeOperation.OUTSIDE_XYZ;
        this.params = params;
        performXYZProbeInternal(0);
    }

    private void performXYZProbeInternal(int stepNumber) throws IllegalStateException {
        String g = GcodeUtils.unitCommand(params.units);

        String g0Abs = "G90 " + g + " G0";
        String g0Rel = "G91 " + g + " G0";

        continuation = () -> performXYZProbeInternal(stepNumber + 1);
        try {
            switch (stepNumber) {
                case 0: {
                    // Reset (0,0,0) to make it easier to retract.
                    updateWCS(params.wcsToUpdate, 0.0, 0.0, 0.0);

                    // Z
                    probe('Z', params.feedRate, params.zSpacing, params.units);
                    break;
                }
                case 1: {
                    gcode(g0Rel + " Z" + retractDistance(params.zSpacing, params.retractAmount));
                    probe('Z', params.feedRateSlow, params.zSpacing, params.units);
                    break;
                }
                case 2: {
                    gcode(g0Abs + " Z0.0");
                    gcode(g0Abs + " X" + -params.xSpacing);
                    Position probeZ = probePositions.get(1).getPositionIn(params.units);
                    double zDir = Math.signum(params.zSpacing);
                    double extent = zDir*Math.min(zDir*(probeZ.z + zDir*params.zPush), zDir*params.zSpacing);
                    System.out.println("zDir " + zDir);
                    System.out.println("extent " + extent);
                    gcode(g0Abs + " Z" + extent); // Probe motion for safety?

                    // X
                    probe('X', params.feedRate, params.xSpacing, params.units);
                    break;
                }
                case 3: {
                    gcode(g0Rel + " X" + retractDistance(params.xSpacing, params.retractAmount));
                    probe('X', params.feedRateSlow, params.xSpacing, params.units);
                    break;
                }
                case 4: {
                    gcode(g0Abs + " X" + -params.xSpacing);
                    gcode(g0Abs + " Y" + -params.ySpacing);
                    Position probeX = probePositions.get(3).getPositionIn(params.units);
                    double xDir = Math.signum(params.xSpacing);
                    double extent = xDir*Math.min(xDir*(probeX.x + xDir*params.xPush), 0);
                    System.out.println("xDir " + xDir);
                    System.out.println("extent " + extent);
                    gcode(g0Abs + " X" + extent);

                    // Y
                    probe('Y', params.feedRate, params.ySpacing, params.units);
                    break;
                }
                case 5: {
                    gcode(g0Rel + " Y" + retractDistance(params.ySpacing, params.retractAmount));
                    probe('Y', params.feedRateSlow, params.ySpacing, params.units);
                    break;
                }
                case 6: {
                    gcode(g0Abs + " Y" + -params.ySpacing);

                    // Back to zero
                    gcode(g0Abs + " Z0.0");
                    gcode(g0Abs + " X0.0 Y0.0");
                    break;
                }
                case 7: {
                    // Once idle, perform calculations.
                    Preconditions.checkState(probePositions.size() == 6, "Unexpected number of probe positions.");

                    Position probeX = probePositions.get(3).getPositionIn(params.units);
                    Position probeY = probePositions.get(5).getPositionIn(params.units);
                    Position probeZ = probePositions.get(1).getPositionIn(params.units);

                    double radius = params.probeDiameter / 2;
                    double xDir = Math.signum(params.xSpacing) * -1;
                    double yDir = Math.signum(params.ySpacing) * -1;
                    double zDir = Math.signum(params.zSpacing) * -1;
                    double xProbedOffset = xDir * (radius + params.xOffset); //DITTO
                    double yProbedOffset = yDir * (radius + params.yOffset); //DITTO
                    double zProbedOffset = zDir * params.zOffset; //DITTO

                    Position startPositionInUnits = params.startPosition.getPositionIn(params.units);
                    updateWCS(params.wcsToUpdate,
                            startPositionInUnits.x - probeX.x + xProbedOffset,
                            startPositionInUnits.y - probeY.y + yProbedOffset,
                            startPositionInUnits.z - probeZ.z + zProbedOffset);
                    break;
                }
                default:
                    throw new UnsupportedOperationException("Invalid step number: " + stepNumber);
            }
        } catch (Exception e) {
            resetProbe();
            logger.log(Level.SEVERE, "Exception during XYZ probe operation.", e);
        }
    }
    
    void performOutsideLinearCenter(ProbeParameters params) throws IllegalStateException {
        validateState();
        currentOperation = ProbeOperation.OUTSIDE_LINEAR_CENTER;
        this.params = params;
        performOutsideLinearCenterInternal(0);
    }
    
    /**
     * Angle 0 = X+, goes counter-clockwise I guess??? //THINK Is that the most obvious default?
     * @param stepNumber
     * @throws IllegalStateException 
     */
    private void performOutsideLinearCenterInternal(int stepNumber) throws IllegalStateException {
        String g = GcodeUtils.unitCommand(params.units);

        String g0Abs = "G90 " + g + " G0";
        String g0Rel = "G91 " + g + " G0";
        
        /*
        // Should find top?  Eh, let z-probe handle that //THINK ...Or SHOULD I?
        Z-
        probe angle+
        probe slow angle+
        Z+
        angle+ other side
        Z-
        probe angle-
        probe slow angle-        
        */

        continuation = () -> performOutsideLinearCenterInternal(stepNumber + 1);
        try {
            switch (stepNumber) {
                case 0: {
                    // Reset (0,0,0) to make it easier to retract.
                    updateWCS(params.wcsToUpdate, 0.0, 0.0, 0.0);

                    // Z-
                    gcode(g0Abs + " Z" + params.zSpacing);
                    
                    // Probe angle+
                    probe(angleToVector(params.angle, params.angleSpacing), params.feedRate, params.units);
                    break;
                }
                case 1: {
                    // Retract angle-
                    gcode(g0Rel + angleToVector(params.angle, retractDistance(params.angleSpacing, params.retractAmount)));
                    // Probe angle+ slow
                    probe(angleToVector(params.angle, params.angleSpacing), params.feedRateSlow, params.units);
                    break;
                }
                case 2: {
                    // Return to safe spot
                    gcode(g0Abs + " X0.0 Y0.0");
                    gcode(g0Abs + " Z0.0");
                    
                    // Move to other side
                    gcode(g0Abs + angleToVector(params.angle, params.angleOtherSide));
                    
                    // Z-
                    gcode(g0Abs + " Z" + params.zSpacing);

                    // Probe angle-
                    probe(angleToVector(params.angle, -params.angleSpacing), params.feedRate, params.units);
                    break;
                }
                case 3: {
                    // Retract angle+
                    gcode(g0Rel + angleToVector(params.angle, retractDistance(-params.angleSpacing, params.retractAmount)));
                    // Probe angle- slow
                    probe(angleToVector(params.angle, -params.angleSpacing), params.feedRateSlow, params.units);
                    break;
                }
                case 4: {
                    // Back up
                    gcode(g0Abs + angleToVector(params.angle, params.angleOtherSide));
                    gcode(g0Abs + " Z0.0");
                    gcode(g0Abs + " X0.0 Y0.0"); //THINK Should we return to 0,0,0?  Seems a waste, but consistency...
                    break;
                }
                case 5: {
                    // Once idle, perform calculations.
                    Preconditions.checkState(probePositions.size() == 4, "Unexpected number of probe positions.");

                    Position probeA = probePositions.get(1).getPositionIn(params.units);
                    Position probeB = probePositions.get(3).getPositionIn(params.units);

                    double radius = params.probeDiameter / 2;
                    
                    double xPosA = probeA.x + angleToX(params.angle, radius) + params.xOffset;
                    double yPosA = probeA.y + angleToY(params.angle, radius) + params.yOffset;
                    double xPosB = probeB.x + angleToX(params.angle, -radius) + params.xOffset;
                    double yPosB = probeB.y + angleToY(params.angle, -radius) + params.yOffset;

                    double xCenter = (xPosA+xPosB)/2;
                    double yCenter = (yPosA+yPosB)/2;
                    
                    double dx = (xPosB-xPosA);
                    double dy = (yPosB-yPosA);
                    double dist = Math.sqrt((dx*dx)+(dy*dy));
                    System.out.println("Distance: " + dist);
                    
                    Position startPositionInUnits = params.startPosition.getPositionIn(params.units);
                    updateWCS(params.wcsToUpdate,
                            startPositionInUnits.x - xCenter, //CHECK Would the fooOffsets mess this up?
                            startPositionInUnits.y - yCenter,
                            null);
                    break;
                }
                default:
                    throw new UnsupportedOperationException("Invalid step number: " + stepNumber);
            }
        } catch (Exception e) {
            resetProbe();
            logger.log(Level.SEVERE, "Exception during outside linear center probe operation.", e);
        }
    }

    void performInsideLinearCenter(ProbeParameters params) throws IllegalStateException {
        validateState();
        currentOperation = ProbeOperation.INSIDE_LINEAR_CENTER;
        this.params = params;
        performInsideLinearCenterInternal(0);
    }
    
    /**
     * Angle 0 = X+, goes counter-clockwise I guess??? //THINK Is that the most obvious default?
     * @param stepNumber
     * @throws IllegalStateException 
     */
    private void performInsideLinearCenterInternal(int stepNumber) throws IllegalStateException {
        String g = GcodeUtils.unitCommand(params.units);

        String g0Abs = "G90 " + g + " G0";
        String g0Rel = "G91 " + g + " G0";
        
        /*
        // Should find top?  Eh, let z-probe handle that //THINK ...Or SHOULD I?
        Z-
        probe angle-
        probe slow angle-
        Z+
        angle+ other side
        Z-
        probe angle+
        probe slow angle+
        */

        continuation = () -> performInsideLinearCenterInternal(stepNumber + 1);
        try {
            switch (stepNumber) {
                case 0: {
                    // Reset (0,0,0) to make it easier to retract.
                    updateWCS(params.wcsToUpdate, 0.0, 0.0, 0.0);

                    // Z-
                    gcode(g0Abs + " Z" + params.zSpacing);
                    
                    // Probe angle-
                    probe(angleToVector(params.angle, -params.angleSpacing), params.feedRate, params.units);
                    break;
                }
                case 1: {
                    // Retract angle+
                    gcode(g0Rel + angleToVector(params.angle, retractDistance(-params.angleSpacing, params.retractAmount)));
                    // Probe angle- slow
                    probe(angleToVector(params.angle, -params.angleSpacing), params.feedRateSlow, params.units);
                    break;
                }
                case 2: {
                    // Return to safe spot
                    gcode(g0Abs + " X0.0 Y0.0");
                    gcode(g0Abs + " Z0.0");
                    
                    // Move to other side
                    gcode(g0Abs + angleToVector(params.angle, params.angleOtherSide));
                    
                    // Z-
                    gcode(g0Abs + " Z" + params.zSpacing);

                    // Probe angle+
                    probe(angleToVector(params.angle, params.angleSpacing), params.feedRate, params.units);
                    break;
                }
                case 3: {
                    // Retract angle-
                    gcode(g0Rel + angleToVector(params.angle, retractDistance(params.angleSpacing, params.retractAmount)));
                    // Probe angle+ slow
                    probe(angleToVector(params.angle, params.angleSpacing), params.feedRateSlow, params.units);
                    break;
                }
                case 4: {
                    // Back up
                    gcode(g0Abs + angleToVector(params.angle, params.angleOtherSide));
                    gcode(g0Abs + " Z0.0");
                    gcode(g0Abs + " X0.0 Y0.0"); //THINK Should we return to 0,0,0?  Seems a waste, but consistency...
                    break;
                }
                case 5: {
                    // Once idle, perform calculations.
                    Preconditions.checkState(probePositions.size() == 4, "Unexpected number of probe positions.");

                    Position probeA = probePositions.get(1).getPositionIn(params.units);
                    Position probeB = probePositions.get(3).getPositionIn(params.units);

                    double radius = params.probeDiameter / 2;
                    
                    double xPosA = probeA.x + angleToX(params.angle, -radius) + params.xOffset;
                    double yPosA = probeA.y + angleToY(params.angle, -radius) + params.yOffset;
                    double xPosB = probeB.x + angleToX(params.angle, radius) + params.xOffset;
                    double yPosB = probeB.y + angleToY(params.angle, radius) + params.yOffset;

                    double xCenter = (xPosA+xPosB)/2;
                    double yCenter = (yPosA+yPosB)/2;
                    
                    double dx = (xPosB-xPosA);
                    double dy = (yPosB-yPosA);
                    double dist = Math.sqrt((dx*dx)+(dy*dy));
                    System.out.println("Distance: " + dist);
                    
                    Position startPositionInUnits = params.startPosition.getPositionIn(params.units);
                    updateWCS(params.wcsToUpdate,
                            startPositionInUnits.x - xCenter, //CHECK Would the fooOffsets mess this up?
                            startPositionInUnits.y - yCenter,
                            null);
                    break;
                }
                default:
                    throw new UnsupportedOperationException("Invalid step number: " + stepNumber);
            }
        } catch (Exception e) {
            resetProbe();
            logger.log(Level.SEVERE, "Exception during inside linear center probe operation.", e);
        }
    }

    void performInsideCircleCenter(ProbeParameters params) throws IllegalStateException {
        validateState();
        currentOperation = ProbeOperation.INSIDE_CIRCLE_CENTER;
        this.params = params;
        System.out.println(angleToVector(params.angle+0, params.angleSpacing));
        System.out.println(angleToVector(params.angle+60, params.angleSpacing));
        System.out.println(angleToVector(params.angle+120, params.angleSpacing));
        performInsideCircleCenterInternal(0);
    }
    
    /**
     * @param stepNumber
     * @throws IllegalStateException 
     */
    private void performInsideCircleCenterInternal(int stepNumber) throws IllegalStateException {
        String g = GcodeUtils.unitCommand(params.units);

        String g0Abs = "G90 " + g + " G0";
        String g0Rel = "G91 " + g + " G0";
        
        /*
        // Should find top?  Eh, let z-probe handle that //THINK ...Or SHOULD I?
        Z-
        probe 0*
        probe slow 0*
        XY0
        probe 120*
        probe slow 120*
        XY0
        probe 240*
        probe slow 240*
        XY0
        Z0
        */

        continuation = () -> performInsideCircleCenterInternal(stepNumber + 1);
        try {
            switch (stepNumber) {
                case 0: {
                    // Reset (0,0,0) to make it easier to retract.
                    updateWCS(params.wcsToUpdate, 0.0, 0.0, 0.0);

                    // Z-
                    gcode(g0Abs + " Z" + params.zSpacing);
                    
                    // Probe 0*
                    probe(angleToVector(params.angle+0, params.angleSpacing), params.feedRate, params.units);
                    break;
                }
                case 1: {
                    // Retract 0*
                    gcode(g0Rel + angleToVector(params.angle+0, retractDistance(params.angleSpacing, params.retractAmount)));
                    // Probe 0* slow
                    probe(angleToVector(params.angle+0, params.angleSpacing), params.feedRateSlow, params.units);
                    break;
                }
                case 2: {
                    // Return to safe spot
                    gcode(g0Abs + " X0.0 Y0.0");
                    
                    // Probe 120*
                    probe(angleToVector(params.angle+120, params.angleSpacing), params.feedRate, params.units);
                    break;
                }
                case 3: {
                    // Retract 120*
                    gcode(g0Rel + angleToVector(params.angle+120, retractDistance(params.angleSpacing, params.retractAmount)));
                    // Probe 120* slow
                    probe(angleToVector(params.angle+120, params.angleSpacing), params.feedRateSlow, params.units);
                    break;
                }
                case 4: {
                    // Return to safe spot
                    gcode(g0Abs + " X0.0 Y0.0");
                    
                    // Probe 240*
                    probe(angleToVector(params.angle+240, params.angleSpacing), params.feedRate, params.units);
                    break;
                }
                case 5: {
                    // Retract 240*
                    gcode(g0Rel + angleToVector(params.angle+240, retractDistance(params.angleSpacing, params.retractAmount)));
                    // Probe 240* slow
                    probe(angleToVector(params.angle+240, params.angleSpacing), params.feedRateSlow, params.units);
                    break;
                }
                case 6: {
                    // Return
                    gcode(g0Abs + " X0.0 Y0.0");
                    gcode(g0Abs + " Z0.0");
                    break;
                }
                case 7: {
                    // Once idle, perform calculations.
                    Preconditions.checkState(probePositions.size() == 6, "Unexpected number of probe positions.");

                    Position probeA = probePositions.get(1).getPositionIn(params.units);
                    Position probeB = probePositions.get(3).getPositionIn(params.units);
                    Position probeC = probePositions.get(5).getPositionIn(params.units);

                    double radius = params.probeDiameter / 2;

                    //CHECK ...Offsets?  I don't really know what they do.
                    Position center = findXYCircleCenter(probeA, probeB, probeC);
                    System.out.println("Center: " + center.x + ", " + center.y);
                    
                    //THINK Wait, what is this.  Rethink this.
                    double dx = (probeA.x-center.x);
                    double dy = (probeA.y-center.y);
                    double dist = Math.sqrt((dx*dx)+(dy*dy))+radius;
                    System.out.println("Diameter: " + dist);
                    
                    Position startPositionInUnits = params.startPosition.getPositionIn(params.units);
                    updateWCS(params.wcsToUpdate,
                            startPositionInUnits.x - center.x,
                            startPositionInUnits.y - center.y,
                            null);
                    break;
                }
                default:
                    throw new UnsupportedOperationException("Invalid step number: " + stepNumber);
            }
        } catch (Exception e) {
            resetProbe();
            logger.log(Level.SEVERE, "Exception during inside circle center probe operation.", e);
        }
    }

    void performOutsideCircleCenter(ProbeParameters params) throws IllegalStateException {
        validateState();
        currentOperation = ProbeOperation.OUTSIDE_CIRCLE_CENTER;
        this.params = params;
//        System.out.println(angleToVector(params.angle+0, params.angleSpacing));
//        System.out.println(angleToVector(params.angle+60, params.angleSpacing));
//        System.out.println(angleToVector(params.angle+120, params.angleSpacing));
        performOutsideCircleCenterInternal(0);
    }
    
    /**
     * @param stepNumber
     * @throws IllegalStateException 
     */
    private void performOutsideCircleCenterInternal(int stepNumber) throws IllegalStateException {
        String g = GcodeUtils.unitCommand(params.units);

        String g0Abs = "G90 " + g + " G0";
        String g0Rel = "G91 " + g + " G0";
        String g0MachineAbs = "G53 G90 " + g + " G0";
        
        continuation = () -> performOutsideCircleCenterInternal(stepNumber + 1);
        try {
            switch (stepNumber) {
                case 0: {
                    System.out.println("POCCI 0");
                    // Reset (0,0,0) to make it easier to retract.
                    updateWCS(params.wcsToUpdate, 0.0, 0.0, 0.0);

                    // Z-
                    gcode(g0Abs + " Z" + params.zSpacing);
                    
                    // Probe angle+
                    probe(angleToVector(params.angle, params.angleSpacing), params.feedRate, params.units);
                    break;
                }
                case 1: {
                    System.out.println("POCCI 1");
                    // Retract angle-
                    gcode(g0Rel + angleToVector(params.angle, retractDistance(params.angleSpacing, params.retractAmount)));
                    // Probe angle+ slow
                    probe(angleToVector(params.angle, params.angleSpacing), params.feedRateSlow, params.units);
                    break;
                }
                case 2: {
                    System.out.println("POCCI 2");
                    // Return to safe spot
                    gcode(g0Abs + " X0.0 Y0.0");
                    gcode(g0Abs + " Z0.0");
                    
                    // Move to other side
                    gcode(g0Abs + angleToVector(params.angle, params.angleOtherSide));
                    
                    // Z-
                    gcode(g0Abs + " Z" + params.zSpacing);

                    // Probe angle-
                    probe(angleToVector(params.angle, -params.angleSpacing), params.feedRate, params.units);
                    break;
                }
                case 3: {
                    System.out.println("POCCI 3");
                    // Retract angle+
                    gcode(g0Rel + angleToVector(params.angle, retractDistance(-params.angleSpacing, params.retractAmount)));
                    // Probe angle- slow
                    probe(angleToVector(params.angle, -params.angleSpacing), params.feedRateSlow, params.units);
                    break;
                }
                case 4: {
                    System.out.println("POCCI 4");
                    // Back up
                    gcode(g0Abs + angleToVector(params.angle, params.angleOtherSide));
                    gcode(g0Abs + " Z0.0");

                    System.out.println("POCCI 5");
                    // Once idle, perform calculations.
                    Preconditions.checkState(probePositions.size() == 4, "Unexpected number of probe positions.");

                    Position probeA = probePositions.get(1).getPositionIn(params.units);
                    Position probeB = probePositions.get(3).getPositionIn(params.units);

                    double radius = params.probeDiameter / 2;
                    
                    double xPosA = probeA.x + angleToX(params.angle, radius) + params.xOffset;
                    double yPosA = probeA.y + angleToY(params.angle, radius) + params.yOffset;
                    double xPosB = probeB.x + angleToX(params.angle, -radius) + params.xOffset;
                    double yPosB = probeB.y + angleToY(params.angle, -radius) + params.yOffset;

                    double xCenter = (xPosA+xPosB)/2;
                    double yCenter = (yPosA+yPosB)/2;

                    System.out.println("POCCI initial center " + xCenter + ", " + yCenter);
                    
                    gcode(g0MachineAbs, "X", f(xCenter), "Y", f(yCenter));

                    System.out.println("POCCI 6");
                    // Move to other side
                    gcode(g0Rel + angleToVector(params.angle-90, params.angleOtherSide/2));
                    
                    // Z-
                    gcode(g0Abs + " Z" + params.zSpacing);

                    // Probe
                    probe(angleToVector(params.angle+90, params.angleSpacing), params.feedRate, params.units);
                    break;
                }
                case 5: {
                    System.out.println("POCCI 7");
                    // Retract
                    gcode(g0Rel + angleToVector(params.angle+90, retractDistance(params.angleSpacing, params.retractAmount)));
                    // Probe slow
                    probe(angleToVector(params.angle+90, params.angleSpacing), params.feedRateSlow, params.units);
                    break;
                }
                case 6: {
                    System.out.println("POCCI 8");
                    // Back up
                    gcode(g0Rel + angleToVector(params.angle+90, retractDistance(params.angleSpacing, params.retractAmount)));
                    gcode(g0Abs + " Z0.0");
                    gcode(g0Abs + " X0.0 Y0.0"); //THINK Go to center instead?
                    break;
                }
                case 7: {
                    System.out.println("POCCI 9");
                    // Once idle, perform calculations.
                    Preconditions.checkState(probePositions.size() == 6, "Unexpected number of probe positions.");

                    Position probeA = probePositions.get(1).getPositionIn(params.units);
                    Position probeB = probePositions.get(3).getPositionIn(params.units);
                    Position probeC = probePositions.get(5).getPositionIn(params.units);

                    double radius = params.probeDiameter / 2;

                    //CHECK ...Offsets?  I don't really know what they do.
                    Position center = findXYCircleCenter(probeA, probeB, probeC);
                    System.out.println("Center: " + center.x + ", " + center.y);
                    
                    //THINK Wait, what is this.  Rethink this.
//                    double dx = (probeA.x-center.x);
//                    double dy = (probeA.y-center.y);
//                    double dist = Math.sqrt((dx*dx)+(dy*dy))+radius; 
//                    System.out.println("Diameter: " + dist);
                    
                    Position startPositionInUnits = params.startPosition.getPositionIn(params.units);
                    updateWCS(params.wcsToUpdate,
                            startPositionInUnits.x - center.x,
                            startPositionInUnits.y - center.y,
                            null);
                    break;
                }
                default:
                    throw new UnsupportedOperationException("Invalid step number: " + stepNumber);
            }
        } catch (Exception e) {
            resetProbe();
            logger.log(Level.SEVERE, "Exception during outside circle center probe operation.", e);
        }
    }
    
    void performMeasureAngle(ProbeParameters params) throws IllegalStateException {
        validateState();
        currentOperation = ProbeOperation.MEASURE_ANGLE;
        this.params = params;
        performMeasureAngleInternal(0);
    }
    
    /**
     * Angle 0 = X+, goes counter-clockwise I guess??? //THINK Is that the most obvious default?
     * @param stepNumber
     * @throws IllegalStateException 
     */
    private void performMeasureAngleInternal(int stepNumber) throws IllegalStateException {
        String g = GcodeUtils.unitCommand(params.units);

        String g0Abs = "G90 " + g + " G0";
        String g0Rel = "G91 " + g + " G0";
        
        /*
        // Should find top?  Eh, let z-probe handle that //THINK ...Or SHOULD I?
        
        probe angle+
        probe slow angle+
        return 0
        strafe ^angle+
        probe angle+
        probe slow angle+
        return angle-
        return 0?
        */

        continuation = () -> performMeasureAngleInternal(stepNumber + 1);
        try {
            switch (stepNumber) {
                case 0: {
                    // Reset (0,0,0) to make it easier to retract.
                    updateWCS(params.wcsToUpdate, 0.0, 0.0, null);

                    // Probe angle+
                    probe(angleToVector(params.angle+90, params.angleSpacing), params.feedRate, params.units);
                    break;
                }
                case 1: {
                    // Retract angle-
                    gcode(g0Rel + angleToVector(params.angle+90, retractDistance(params.angleSpacing, params.retractAmount)));
                    // Probe angle+ slow
                    probe(angleToVector(params.angle+90, params.angleSpacing), params.feedRateSlow, params.units);
                    break;
                }
                case 2: {
                    // Return to safe spot
                    gcode(g0Abs + " X0.0 Y0.0");
                    
                    // Strafe
                    gcode(g0Abs + angleToVector(params.angle, params.angleOtherSide));
                    
                    // Probe angle+
                    probe(angleToVector(params.angle+90, params.angleSpacing), params.feedRate, params.units);
                    break;
                }
                case 3: {
                    // Retract angle-
                    gcode(g0Rel + angleToVector(params.angle+90, retractDistance(params.angleSpacing, params.retractAmount)));
                    // Probe angle+ slow
                    probe(angleToVector(params.angle+90, params.angleSpacing), params.feedRateSlow, params.units);
                    break;
                }
                case 4: {
                    // Return
                    gcode(g0Abs + angleToVector(params.angle, params.angleOtherSide));
                    gcode(g0Abs + " X0.0 Y0.0"); //THINK Should we return to 0,0,0?  Seems a waste, but consistency...
                    break;
                }
                case 5: {
                    // Once idle, perform calculations.
                    Preconditions.checkState(probePositions.size() == 4, "Unexpected number of probe positions.");

                    Position probeA = probePositions.get(1).getPositionIn(params.units);
                    Position probeB = probePositions.get(3).getPositionIn(params.units);
                    double angle = Math.atan2(probeB.y-probeA.y, probeB.x-probeA.x)*360/(2*Math.PI);
                    if (params.callback != null) {
                        params.callback.accept(angle);
                    }
                    break;
                }
                default:
                    throw new UnsupportedOperationException("Invalid step number: " + stepNumber);
            }
        } catch (Exception e) {
            resetProbe();
            logger.log(Level.SEVERE, "Exception during measure angle operation.", e);
        }
    }
    
    void performCutCylinderShell(ProbeParameters params) throws IllegalStateException {
        validateState();
        currentOperation = ProbeOperation.CYLINDER_SHELL;
        this.params = params;
        performCutCylinderShellInternal(0);
    }

    /**
     * 1,1,1,.312,(0)*
     * @param stepNumber
     * @throws IllegalStateException 
     */
    private void performCutCylinderShellInternal(int stepNumber) throws IllegalStateException {
        String u = GcodeUtils.unitCommand(params.units);

        String ABS = "G90 " + u;
        String REL = "G91 " + u;
        String FAST = "G0"; //CHECK This seems to ignore feed entirely - does that get set somewhere at some point, or is it wholly independent of our config?
        String SLOW = "G1";
        String ARC = params.cutCCW ? "G3" : "G2";
        
        boolean helix = true; //RAINY Optionize
        int finalPasses = 2; // If < 1, probably won't actually finish cutting.  //RAINY Optionize
                
        continuation = () -> performCutCylinderShellInternal(stepNumber + 1);
        try {
            switch (stepNumber) {
                case 0: {
                    // Reset (_, _, 0) to make it easier to retract.
                    updateWCS(params.wcsToUpdate, 0.0, 0.0, 0.0);

                    //RAINY This "have one params class for everything" is feeling more and more incorrect
                    //THINK Should offset be factored in, or no?

                    double r = params.cutDiameter / 2; // Compensation for tool diameter is done when setting params
                    
                    // CW Arc
                    //THINK Apply rotations?
                    //gcode(ABS, FAST, "Y"+f(r));
                    gcode(ABS, SLOW, "Y"+f(r), "F"+f(params.cutFeedRate));

                    //THINK The extra negatives are a bit weird
                    //CHECK How much gcode can we send at once?  Can/should we break it up?
                    double z = 0;

                    gcode(ABS, SLOW, "Z"+f(-params.cutSkipZ), "F"+f(params.cutFeedRate));
                    z -= params.cutSkipZ;
                    
                    if (helix) {
                        while (true) {
                            if (z + params.cutDepthZ <= EPS) { // If near or beyond zero distance left
                                break;
                            }
                            double target;
                            if ((z - (-params.cutDepthZ)) < params.cutLayerThicknessZ) {
                                target = -params.cutDepthZ;
                            } else {
                                target = z - params.cutLayerThicknessZ;
                            }
                            // One half of the cut
                            gcode(ARC,"X"+f(0),"Y"+f(-r),"I"+f(0),"J"+f(-r),"Z"+f((z+target)/2),"F"+f(params.cutFeedRate));
                            // Second half
                            gcode(ARC,"X"+f(0),"Y"+f(r),"I"+f(0),"J"+f(r),"Z"+f(target),"F"+f(params.cutFeedRate));
                            z = target;
                        }
                        // We want a final flat cut at bottom depth
                        for (int i = 0; i < finalPasses; i++) {
                            // One half of the cut
                            gcode(ARC,"X"+f(0),"Y"+f(-r),"I"+f(0),"J"+f(-r),"F"+f(params.cutFeedRate));
                            // Second half
                            gcode(ARC,"X"+f(0),"Y"+f(r),"I"+f(0),"J"+f(r),"F"+f(params.cutFeedRate));
                        }
                    } else {
                        while (true) {
                            if ((z - (-params.cutDepthZ)) < params.cutLayerThicknessZ) {
                                gcode(ABS, SLOW, "Z"+f(-params.cutDepthZ), "F"+f(params.cutFeedRate));
                                z = -params.cutDepthZ;
                            } else {
                                gcode(REL, SLOW, "Z"+f(-params.cutLayerThicknessZ), "F"+f(params.cutFeedRate));
                                z -= params.cutLayerThicknessZ;
                            }
                            if (z + params.cutDepthZ <= EPS) { // If near or beyond zero distance left
                                break;
                            }
                            for (int i = 0; i < finalPasses; i++) {
                                // One half of the cut
                                gcode(ARC,"X"+f(0),"Y"+f(-r),"I"+f(0),"J"+f(-r),"F"+f(params.cutFeedRate));
                                // Second half
                                gcode(ARC,"X"+f(0),"Y"+f(r),"I"+f(0),"J"+f(r),"F"+f(params.cutFeedRate));
                            }
                        }
                        // We want a final cut at bottom depth
                        // One half of the cut
                        gcode(ARC,"X"+f(0),"Y"+f(-r),"I"+f(0),"J"+f(-r),"F"+f(params.cutFeedRate));
                        // Second half
                        gcode(ARC,"X"+f(0),"Y"+f(r),"I"+f(0),"J"+f(r),"F"+f(params.cutFeedRate));
                    }
                    
                    // Return
                    gcode(ABS, FAST, "Z0");
                    gcode(ABS, FAST, "X0 Y0");
                    break;
                }
                case 1: {
                    // Done, I guess?
                    break;
                }
                default:
                    throw new UnsupportedOperationException("Invalid step number: " + stepNumber);
            }
        } catch (Exception e) {
            resetProbe();
            logger.log(Level.SEVERE, "Exception during cut cylinder shell operation.", e);
        }
    }

    void performCutBoxSolid(ProbeParameters params) throws IllegalStateException {
        validateState();
        currentOperation = ProbeOperation.BOX_SOLID;
        this.params = params;
        performCutBoxSolidInternal(0);
    }

    /**
     * //RAINY Inside vs outside?...not sure that makes as much sense with a solid box.
     * /// Maybe cutting a box-hole into st vs cutting st into a box-shape.
     * @param stepNumber
     * @throws IllegalStateException 
     */
    private void performCutBoxSolidInternal(int stepNumber) throws IllegalStateException {
        String u = GcodeUtils.unitCommand(params.units);

        String ABS = "G90 " + u;
        String REL = "G91 " + u;
        String FAST = "G0"; //CHECK This seems to ignore feed entirely - does that get set somewhere at some point, or is it wholly independent of our config?
        String SLOW = "G1";
        String ARC = params.cutCCW ? "G3" : "G2";
        
        continuation = () -> performCutBoxSolidInternal(stepNumber + 1);
        try {
            switch (stepNumber) {
                case 0: {
                    // Reset (_, _, 0) to make it easier to retract.
                    updateWCS(params.wcsToUpdate, 0.0, 0.0, 0.0);

                    //RAINY This "have one params class for everything" is feeling more and more incorrect
                    //THINK Should offset be factored in, or no?

                    String AN, BN, CN; // Name
                    double AM, BM, CM; // Max
                    double AS, BS, CS; // Sign
                    double AD, BD, CD; // Delta
                    
                    switch (params.cutBoxOrder) {
                        case XYZ: {
                            AN = "X";
                            BN = "Y";
                            CN = "Z";
                            AM = Math.abs(params.xSpacing);
                            BM = Math.abs(params.ySpacing);
                            CM = Math.abs(params.zSpacing);
                            AS = Math.signum(params.xSpacing);
                            BS = Math.signum(params.ySpacing);
                            CS = Math.signum(params.zSpacing);
                            AD = params.xPush;
                            BD = params.yPush;
                            CD = params.zPush;
                            break;
                        }
                        case XZY: {
                            AN = "X";
                            BN = "Z";
                            CN = "Y";
                            AM = Math.abs(params.xSpacing);
                            BM = Math.abs(params.zSpacing);
                            CM = Math.abs(params.ySpacing);
                            AS = Math.signum(params.xSpacing);
                            BS = Math.signum(params.zSpacing);
                            CS = Math.signum(params.ySpacing);
                            AD = params.xPush;
                            BD = params.zPush;
                            CD = params.yPush;
                            break;
                        }
                        case YXZ: {
                            AN = "Y";
                            BN = "X";
                            CN = "Z";
                            AM = Math.abs(params.ySpacing);
                            BM = Math.abs(params.xSpacing);
                            CM = Math.abs(params.zSpacing);
                            AS = Math.signum(params.ySpacing);
                            BS = Math.signum(params.xSpacing);
                            CS = Math.signum(params.zSpacing);
                            AD = params.yPush;
                            BD = params.xPush;
                            CD = params.zPush;
                            break;
                        }
                        case YZX: {
                            AN = "Y";
                            BN = "Z";
                            CN = "X";
                            AM = Math.abs(params.ySpacing);
                            BM = Math.abs(params.zSpacing);
                            CM = Math.abs(params.xSpacing);
                            AS = Math.signum(params.ySpacing);
                            BS = Math.signum(params.zSpacing);
                            CS = Math.signum(params.xSpacing);
                            AD = params.yPush;
                            BD = params.zPush;
                            CD = params.xPush;
                            break;
                        }
                        case ZXY: {
                            AN = "Z";
                            BN = "X";
                            CN = "Y";
                            AM = Math.abs(params.zSpacing);
                            BM = Math.abs(params.xSpacing);
                            CM = Math.abs(params.ySpacing);
                            AS = Math.signum(params.zSpacing);
                            BS = Math.signum(params.xSpacing);
                            CS = Math.signum(params.ySpacing);
                            AD = params.zPush;
                            BD = params.xPush;
                            CD = params.yPush;
                            break;
                        }
                        case ZYX: {
                            AN = "Z";
                            BN = "Y";
                            CN = "X";
                            AM = Math.abs(params.zSpacing);
                            BM = Math.abs(params.ySpacing);
                            CM = Math.abs(params.xSpacing);
                            AS = Math.signum(params.zSpacing);
                            BS = Math.signum(params.ySpacing);
                            CS = Math.signum(params.xSpacing);
                            AD = params.zPush;
                            BD = params.yPush;
                            CD = params.xPush;
                            break;
                        }
                        default: {
                            throw new UnsupportedOperationException("Invalid box order: " + params.cutBoxOrder);
                        }
                    }
                    
                    double a = 0;
                    double b = 0;
                    double c = 0;
                    int ad = 1;
                    int bd = 1;
                    int cd = 1;
                    int phase = 0;
                    
                    //CHECK Does it work with 0 width?
                    phaseLoop: while (true) {
                        double target;
                        switch (phase) {
                            case 0: { // A
                                if (ad > 0) {
                                    gcode(ABS, SLOW, AN+f(AS*AM), "F"+params.cutFeedRate);
                                } else {
                                    gcode(ABS, SLOW, AN+f(0.), "F"+params.cutFeedRate);
                                }
                                ad *= -1;
                                phase++;
                                break;
                            }
                            case 1: { // B
                                if (bd > 0) {
                                    target = BM;
                                } else {
                                    target = 0;
                                }
                                if (Math.abs(target-b) > EPS) {
                                    // Not done yet
                                    if (Math.abs(target-b)-BD <= EPS) { // Is close?
                                        b = target;
                                    } else {
                                        b += bd*BD;
                                    }
                                    gcode(ABS, SLOW, BN+f(BS*b), "F"+params.cutFeedRate);
                                    phase = 0;
                                } else {
                                    // Done
                                    bd *= -1;
                                    phase++;
                                }
                                break;
                            }
                            case 2: { // C
                                if (cd > 0) {
                                    target = CM;
                                } else {
                                    target = 0;
                                }
                                if (Math.abs(target-c) > EPS) {
                                    // Not done yet
                                    if (Math.abs(target-c)-CD <= EPS) { // Is close?
                                        c = target;
                                    } else {
                                        c += cd*CD;
                                    }
                                    gcode(ABS, SLOW, CN+f(CS*c), "F"+params.cutFeedRate);
                                    phase = 0;
                                } else {
                                    // Done
                                    cd *= -1;
                                    phase++;
                                }
                                break;
                            }
                            default: {
                                break phaseLoop;
                            }
                        }
                    }

                    // Return
                    gcode(ABS, FAST, "Z0");
                    gcode(ABS, FAST, "X0 Y0");
                    break;
                }
                case 1: {
                    // Done, I guess?
                    break;
                }
                default:
                    throw new UnsupportedOperationException("Invalid step number: " + stepNumber);
            }
        } catch (Exception e) {
            resetProbe();
            logger.log(Level.SEVERE, "Exception during cut box solid operation.", e);
        }
    }
    
    void performMoveXPlus(ProbeParameters params) throws IllegalStateException {
        validateState();
        currentOperation = ProbeOperation.MOVE_XP;
        this.params = params;
        performMoveXPlusInternal(0);
    }
    
    /**
     * Angle 0 = X+, goes counter-clockwise I guess??? //THINK Is that the most obvious default?
     * @param stepNumber
     * @throws IllegalStateException 
     */
    private void performMoveXPlusInternal(int stepNumber) throws IllegalStateException {
        String u = GcodeUtils.unitCommand(params.units);

        String ABS = "G90 " + u;
        String REL = "G91 " + u;
        String FAST = "G0"; //CHECK This seems to ignore feed entirely - does that get set somewhere at some point, or is it wholly independent of our config?
        String SLOW = "G1";
        
        continuation = () -> performMoveXPlusInternal(stepNumber + 1);
        try {
            switch (stepNumber) {
                case 0: {
                    // Move angle+
                    gcode(REL, SLOW, angleToVector(params.angle, params.xSpacing), "F"+params.feedRate);
                    break;
                }
                case 1: {
                    // Done?
                    break;
                }
                default:
                    throw new UnsupportedOperationException("Invalid step number: " + stepNumber);
            }
        } catch (Exception e) {
            resetProbe();
            logger.log(Level.SEVERE, "Exception during move operation.", e);
        }
    }
    
    void performLatheRoundFace(ProbeParameters params) throws IllegalStateException {
        validateState();
        currentOperation = ProbeOperation.LATHE_ROUND_FACE;
        this.params = params;
        performLatheRoundFaceInternal(0);
    }
    
    /**
     * X+ DOC, Z- Zsize, until Xsize
     * 1,1,1,.312,(0)*
     * @param stepNumber
     * @throws IllegalStateException 
     */
    private void performLatheRoundFaceInternal(int stepNumber) throws IllegalStateException {
        String u = GcodeUtils.unitCommand(params.units);

        String ABS = "G90 " + u;
        String REL = "G91 " + u;
        String FAST = "G0"; //CHECK This seems to ignore feed entirely - does that get set somewhere at some point, or is it wholly independent of our config?
        String SLOW = "G1";
        
        continuation = () -> performLatheRoundFaceInternal(stepNumber + 1);
        try {
            switch (stepNumber) {
                case 0: {
                    // Reset (_, _, 0) to make it easier to retract.
                    updateWCS(params.wcsToUpdate, 0.0, 0.0, 0.0);

                    //RAINY This "have one params class for everything" is feeling more and more incorrect
                    //THINK Should offset be factored in, or no?

                    //THINK The extra negatives are a bit weird
                    //CHECK How much gcode can we send at once?  Can/should we break it up?
                    double x = 0;
                    double dir = Math.signum(params.xSpacing);
                    int finalPasses = 0;
                    
                    while (true) {
                        //THINK Make sure all stuff handles internal ops
                        if (dir*x - dir*params.xSpacing >= -EPS) { // If near or beyond zero distance left
                            break;
                        }
                        double target;
                        if (dir*(params.xSpacing - x) < params.cutLayerThicknessZ) {
                            target = params.xSpacing;
                        } else {
                            target = x + dir*params.cutLayerThicknessZ;
                        }
                        System.out.println("X: "+target);
                        gcode(ABS, SLOW, "X"+f(target), "F"+params.cutFeedRate);
                        gcode(ABS, SLOW, "Z"+f(params.zSpacing), "F"+params.cutFeedRate);
                        gcode(REL, FAST, "X"+f(-dir*params.retractAmount));
                        gcode(ABS, FAST, "Z0");
                        x = target;
                    }
                    // We MAY want a final flat cut at bottom depth.  For lathe work, I'm not sure.
                    for (int i = 0; i < finalPasses; i++) {
                        gcode(ABS, SLOW, "X"+f(x), "F"+params.cutFeedRate);
                        gcode(ABS, SLOW, "Z"+f(params.zSpacing), "F"+params.cutFeedRate);
                        gcode(REL, FAST, "X"+f(-dir*params.retractAmount));
                        gcode(ABS, FAST, "Z0");
                    }
                    
                    // Return
                    gcode(ABS, FAST, "X0 Z0"); //THINK Pull out at a 45* angle?
                    break;
                }
                case 1: {
                    // Done?
                    break;
                }
                default:
                    throw new UnsupportedOperationException("Invalid step number: " + stepNumber);
            }
        } catch (Exception e) {
            resetProbe();
            logger.log(Level.SEVERE, "Exception during lathe round face operation.", e);
        }
    }

    void performLatheFlatFace(ProbeParameters params) throws IllegalStateException {
        validateState();
        currentOperation = ProbeOperation.LATHE_FLAT_FACE;
        this.params = params;
        performLatheFlatFaceInternal(0);
    }
    
    /**
     * Z- DOC, X+ Xsize, until Zsize
     * 1,1,1,.312,(0)*
     * @param stepNumber
     * @throws IllegalStateException 
     */
    private void performLatheFlatFaceInternal(int stepNumber) throws IllegalStateException {
        String u = GcodeUtils.unitCommand(params.units);

        String ABS = "G90 " + u;
        String REL = "G91 " + u;
        String FAST = "G0"; //CHECK This seems to ignore feed entirely - does that get set somewhere at some point, or is it wholly independent of our config?
        String SLOW = "G1";
        
        continuation = () -> performLatheFlatFaceInternal(stepNumber + 1);
        try {
            switch (stepNumber) {
                case 0: {
                    // Reset (_, _, 0) to make it easier to retract.
                    updateWCS(params.wcsToUpdate, 0.0, 0.0, 0.0);

                    //RAINY This "have one params class for everything" is feeling more and more incorrect
                    //THINK Should offset be factored in, or no?

                    //THINK The extra negatives are a bit weird
                    //CHECK How much gcode can we send at once?  Can/should we break it up?
                    double z = 0;
                    double dir = Math.signum(params.zSpacing);
                    int finalPasses = 0;
                    
                    while (true) {
                        //THINK Make sure all stuff handles internal ops
                        if (dir*z - dir*params.zSpacing >= -EPS) { // If near or beyond zero distance left
                            break;
                        }
                        double target;
                        if (dir*(params.zSpacing - z) < params.cutLayerThicknessZ) {
                            target = params.zSpacing;
                        } else {
                            target = z + dir*params.cutLayerThicknessZ;
                        }
                        gcode(ABS, SLOW, "Z"+f(target), "F"+params.cutFeedRate);
                        gcode(ABS, SLOW, "X"+f(params.xSpacing), "F"+params.cutFeedRate);
                        gcode(REL, FAST, "Z"+f(-dir*params.retractAmount));
                        gcode(ABS, FAST, "X0");
                        z = target;
                    }
                    // We MAY want a final flat cut at bottom depth.  For lathe work, I'm not sure.
                    for (int i = 0; i < finalPasses; i++) {
                        gcode(ABS, SLOW, "Z"+f(z), "F"+params.cutFeedRate);
                        gcode(ABS, SLOW, "X"+f(params.xSpacing), "F"+params.cutFeedRate);
                        gcode(REL, FAST, "Z"+f(-dir*params.retractAmount));
                        gcode(ABS, FAST, "X0");
                    }
                    
                    // Return
                    gcode(ABS, FAST, "X0 Z0"); //THINK Pull out at a 45* angle?
                    break;
                }
                case 1: {
                    // Done?
                    break;
                }
                default:
                    throw new UnsupportedOperationException("Invalid step number: " + stepNumber);
            }
        } catch (Exception e) {
            resetProbe();
            logger.log(Level.SEVERE, "Exception during lathe round face operation.", e);
        }
    }

    void performLatheTaper(ProbeParameters params) throws IllegalStateException {
        validateState();
        currentOperation = ProbeOperation.LATHE_TAPER;
        this.params = params;
        performLatheTaperInternal(0);
    }
    
    /**
     * Xdir for DOC at TAngle, Zdir and -Xdir at Angle until X0, retract X, to Z0 and Xtarget-retract, repeat until DOC correct
     * 1,1,1,.312,(0)*
     * @param stepNumber
     * @throws IllegalStateException 
     */
    private void performLatheTaperInternal(int stepNumber) throws IllegalStateException {
        String u = GcodeUtils.unitCommand(params.units);

        String ABS = "G90 " + u;
        String REL = "G91 " + u;
        String FAST = "G0"; //CHECK This seems to ignore feed entirely - does that get set somewhere at some point, or is it wholly independent of our config?
        String SLOW = "G1";
        
        continuation = () -> performLatheTaperInternal(stepNumber + 1);
        try {
            switch (stepNumber) {
                case 0: {
                    //CHECK Angle negative, what mean, should use?
                    updateWCS(params.wcsToUpdate, 0.0, 0.0, 0.0);

                    //RAINY This "have one params class for everything" is feeling more and more incorrect
                    //THINK Should offset be factored in, or no?

                    //THINK The extra negatives are a bit weird
                    //CHECK How much gcode can we send at once?  Can/should we break it up?
                    double x = 0;
                    double xdir = Math.signum(params.xSpacing);
                    int finalPasses = 0;
                    double a = params.angle*2*Math.PI/360.0;
                    double xcut = params.cutLayerThicknessZ / Math.cos(a);
                    
                    /*
                    Xdir for DOC at TAngle
                    Zdir and -Xdir at Angle until X0 //RAINY permit max Z setting
                    retract X
                    to Z0 and Xtarget-retract
                    repeat until DOC correct
                    */
                    
                    while (true) {
                        //THINK Make sure all stuff handles internal ops
                        if (xdir*x - xdir*params.xSpacing >= -EPS) { // If near or beyond zero distance left
                            break;
                        }
                        double target;
                        if (xdir*(params.xSpacing - x) < xcut) {
                            target = params.xSpacing;
                        } else {
                            target = x + xdir*xcut;
                        }
                        gcode(ABS, SLOW, "X"+f(target), "F"+params.cutFeedRate);
                        gcode(ABS, SLOW, "X0", "Z"+f(-target/Math.tan(a)), "F"+params.cutFeedRate);
                        gcode(REL, FAST, "X"+f(-xdir*params.retractAmount));
                        gcode(ABS, FAST, "X"+f(target-xdir*params.retractAmount), "Z0");
                        x = target;
                    }
                    // We MAY want a final flat cut at bottom depth.  For lathe work, I'm not sure.
                    for (int i = 0; i < finalPasses; i++) {
                        gcode(ABS, SLOW, "X"+f(x), "F"+params.cutFeedRate);
                        gcode(ABS, SLOW, "X0", "Z"+f(-x/Math.tan(a)), "F"+params.cutFeedRate);
                        gcode(REL, FAST, "X"+f(-xdir*params.retractAmount));
                        gcode(ABS, FAST, "X"+f(x-xdir*params.retractAmount), "Z0");
                    }
                    
                    // Return
                    gcode(ABS, FAST, "X0 Z0"); //THINK Pull out at a 45* angle?
                    break;
                }
                case 1: {
                    // Done?
                    break;
                }
                default:
                    throw new UnsupportedOperationException("Invalid step number: " + stepNumber);
            }
        } catch (Exception e) {
            resetProbe();
            logger.log(Level.SEVERE, "Exception during lathe round face operation.", e);
        }
    }
    
    
    
    

    
    
    
    
    // Support functions

    /**
     * 
     * @param angle In degrees
     * @param distance In units
     * @return e.g. " X0.5 Y-2.3441"
     */
    String angleToVector(double angle, double distance) {
        return " X" + Utils.formatter.format(angleToX(angle, distance)) + " Y" + Utils.formatter.format(angleToY(angle, distance));
    }

    double angleToX(double angle, double distance) {
        return distance*Math.cos(angle*2*Math.PI/360.0);
    }

    double angleToY(double angle, double distance) {
        return distance*Math.sin(angle*2*Math.PI/360.0);
    }

    // https://math.stackexchange.com/a/4000949
    Position findXYCircleCenter(Position a, Position b, Position c) {
        //MISC Could check the points are all on one plane....
        double x1 = a.x;
        double x21 = x1*x1;
        double y1 = a.y;
        double y21 = y1*y1;
        double x2 = b.x;
        double x22 = x2*x2;
        double y2 = b.y;
        double y22 = y2*y2;
        double x3 = c.x;
        double x23 = x3*x3;
        double y3 = c.y;
        double y23 = y3*y3;
        double A = x1*(y2-y3)-y1*(x2-x3)+x2*y3-x3*y2;
        double B = (x21+y21)*(y3-y2)+(x22+y22)*(y1-y3)+(x23+y23)*(y2-y1);
        double C = (x21+y21)*(x2-x3)+(x22+y22)*(x3-x1)+(x23+y23)*(x1-x2);
        double D = (x21+y21)*(x3*y2-x2*y3)+(x22+y22)*(x1*y3-x3*y1)+(x23+y23)*(x2*y1-x1*y2);
        double xc = -B/(2*A);
        double yc = -C/(2*A);
        double r = Math.sqrt((B*B+C*C-4*A*D)/(4*A*A));
        return new Position(xc, yc, a.z); //MISC a.z seems slightly better than 0....
    }
    
    private String f(double d) {
        return Utils.formatter.format(d);
    }
    
    private void updateWCS(WorkCoordinateSystem wcs, Double x, Double y, Double z) throws Exception {
        StringBuilder sb = new StringBuilder();
        // Format the x, y, and z to prevent printing with double "E" notation.
        if (x != null) {
            sb.append("X").append(Utils.formatter.format(x));
        }
        if (y != null) {
            sb.append("Y").append(Utils.formatter.format(y));
        }
        if (z != null) {
            sb.append("Z").append(Utils.formatter.format(z));
        }

        gcode(String.format(WCS_PATTERN, wcs.getPValue(), sb.toString()));
    }

    /**
     * Send a gcode command and handle any possible error.
     */
    private void gcode(String... s) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append(s[0]);
        for (int i = 1; i < s.length; i++) {
            sb.append(" " + s[i]);
        }
        gcodeCallback.accept(sb.toString());
        backend.sendGcodeCommand(true, sb.toString());
    }

    /**
     * Send a probe command and handle any possible error.
     */
    private void probe(char axis, double rate, double distance, Units u) throws Exception {
        gcodeCallback.accept("; probe not captured");
        backend.probe(String.valueOf(axis), rate, distance, u);
    }

    private void probe(String target, double rate, Units u) throws Exception {
        gcodeCallback.accept("; probe not captured");
        backend.probe(target, rate, u);
    }
    
    private void probe(double x, double y, double z, double rate, Units u) throws Exception {
        gcodeCallback.accept("; probe not captured");
        backend.probe(x, y, z, rate, u);
    }

    @Override
    public void UGSEvent(UGSEvent evt) {
        if (this.currentOperation == ProbeOperation.NONE) return;

        if (evt instanceof ControllerStateEvent) {
            ControllerStateEvent controllerStateEvent = (ControllerStateEvent) evt;
            ControllerState state = controllerStateEvent.getState();
            if (state == ControllerState.DISCONNECTED) {
                resetProbe();
            } else if (state == ControllerState.IDLE) {
                // Finalize
                if (this.currentOperation.getNumProbes() <= this.probePositions.size()) { //CHECK Is this the right way round?  How could it have possibly been wrong this whole time???
                    try {
                        continuation.execute();
                    } catch (Exception e) {
                        logger.log(Level.SEVERE,
                                "Exception finalizing " + this.currentOperation + " probe operation.", e);
                    } finally {
                        params.endPosition = this.backend.getMachinePosition();
                        this.resetProbe();
                    }
                } else {
                    System.out.println("Operation ending?: " + this.currentOperation.getNumProbes() + " > " + this.probePositions.size());
                }
            }
        } else if (evt instanceof ProbeEvent) {
            Position position = ((ProbeEvent)evt).getProbePosition();
            System.out.println("(ProbeEvent).getProbePosition: " + position);
            this.probePositions.add(position);
            try {
                continuation.execute();
            } catch (Exception e) {
                System.err.println("ERROR "+ e);
                logger.log(Level.SEVERE,
                        "Exception during " + this.currentOperation + " probe operation.", e);
                resetProbe();
            }
        }
    }
}
