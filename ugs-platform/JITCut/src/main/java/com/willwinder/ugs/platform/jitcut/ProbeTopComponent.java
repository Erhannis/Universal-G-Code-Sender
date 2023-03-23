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

import static com.willwinder.universalgcodesender.model.WorkCoordinateSystem.G54;
import static com.willwinder.universalgcodesender.model.WorkCoordinateSystem.G55;
import static com.willwinder.universalgcodesender.model.WorkCoordinateSystem.G56;
import static com.willwinder.universalgcodesender.model.WorkCoordinateSystem.G57;
import static com.willwinder.universalgcodesender.model.WorkCoordinateSystem.G58;
import static com.willwinder.universalgcodesender.model.WorkCoordinateSystem.G59;
import static com.willwinder.universalgcodesender.utils.SwingHelpers.getDouble;

import com.google.gson.Gson;
import com.willwinder.ugs.nbm.visualizer.shared.Renderable;
import com.willwinder.ugs.nbm.visualizer.shared.RenderableUtils;
import com.willwinder.ugs.nbp.lib.lookup.CentralLookup;
import com.willwinder.ugs.nbp.lib.services.LocalizingService;
import static com.willwinder.ugs.nbp.lib.services.LocalizingService.lang;
import com.willwinder.ugs.nbp.lib.services.TopComponentLocalizer;
import com.willwinder.ugs.platform.jitcut.ProbeService.ProbeParameters;
import com.willwinder.ugs.platform.jitcut.renderable.CornerProbePathPreview;
import com.willwinder.ugs.platform.jitcut.renderable.ZProbePathPreview;
import com.willwinder.universalgcodesender.i18n.Localization;
import com.willwinder.universalgcodesender.listeners.UGSEventListener;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.UGSEvent;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import com.willwinder.universalgcodesender.model.WorkCoordinateSystem;

import com.willwinder.universalgcodesender.model.events.ControllerStateEvent;
import net.miginfocom.swing.MigLayout;

import org.netbeans.api.settings.ConvertAsProperties;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.windows.TopComponent;

import java.awt.*;

import javax.swing.*;
import org.apache.commons.lang3.StringUtils;
import org.openide.modules.OnStart;
import org.openide.windows.WindowManager;

/**
 * Top component which displays something.
 */
@ConvertAsProperties(
        dtd = "-//com.willwinder.ugs.platform.jitcut//CornerProbeTopComponent//EN",
        autostore = false
)
@TopComponent.Description(
        preferredID = ProbeTopComponent.preferredId,
        //iconBase="SET/PATH/TO/ICON/HERE",
        persistenceType = TopComponent.PERSISTENCE_ALWAYS
)
@TopComponent.Registration(mode = "output", openAtStartup = false)
@ActionID(
        category = ProbeTopComponent.ProbeCategory,
        id = ProbeTopComponent.ProbeActionId)
@ActionReference(path = LocalizingService.MENU_WINDOW_PLUGIN)
@TopComponent.OpenActionRegistration(
        displayName = "Probe",
        preferredID = ProbeTopComponent.preferredId
)
public final class ProbeTopComponent extends TopComponent implements UGSEventListener {
    public static final String preferredId = "AdvancedProbeTopComponent";
    private Renderable active = null;
    private CornerProbePathPreview cornerRenderable = new CornerProbePathPreview(
            Localization.getString("probe.visualizer.corner-preview"));
    private ZProbePathPreview zRenderable = new ZProbePathPreview(
            Localization.getString("probe.visualizer.z-preview"));

    private static final String X_OFFSET = Localization.getString("autoleveler.option.offset-x") + ":";
    private static final String Y_OFFSET = Localization.getString("autoleveler.option.offset-y") + ":";
    //private static final String Z_OFFSET = Localization.getString("autoleveler.option.offset-z") + ":";
    private static final String Z_OFFSET = Localization.getString("probe.plate-thickness");
    private static final String X_PUSH = "Push X:";
    private static final String Y_PUSH = "Push Y:";
    private static final String Z_PUSH = "Push Z:";
    private static final String X_DISTANCE = Localization.getString("probe.x-distance") + ":";
    private static final String Y_DISTANCE = Localization.getString("probe.y-distance") + ":";
    private static final String Z_DISTANCE = Localization.getString("probe.probe-distance") + ":";

    public final static String ProbeTitle = "JITCut"; //RAINY Localization?
    public final static String ProbeTooltip = "A plugin to help perform on-the-fly cuts and design."; //DITTO
    public final static String ProbeActionId = "com.willwinder.ugs.platform.jitcut.ProbeTopComponent.renamed";
    public final static String ProbeCategory = LocalizingService.CATEGORY_WINDOW;


    // xyz tab
    private static final String XYZ_TAB = "XYZ";
    private SpinnerNumberModel xyzXDistanceModel;
    private SpinnerNumberModel xyzYDistanceModel;
    private SpinnerNumberModel xyzZDistanceModel;
    private SpinnerNumberModel xyzXOffsetModel;
    private SpinnerNumberModel xyzYOffsetModel;
    private SpinnerNumberModel xyzZOffsetModel;
    private SpinnerNumberModel xyzXPushModel;
    private SpinnerNumberModel xyzYPushModel;
    private SpinnerNumberModel xyzZPushModel;
    private final JButton measureXYZ = new JButton(Localization.getString("probe.measure.outside-corner"));

    // outside tab
    private static final String OUTSIDE_TAB = "XY";
    private SpinnerNumberModel outsideXDistanceModel;
    private SpinnerNumberModel outsideYDistanceModel;
    private SpinnerNumberModel outsideXOffsetModel;
    private SpinnerNumberModel outsideYOffsetModel;
    private SpinnerNumberModel outsideXPushModel;
    private SpinnerNumberModel outsideYPushModel;
    private final JButton measureOutside = new JButton(Localization.getString("probe.measure.outside-corner"));

    // z-probe tab
    private static final String Z_TAB = "Z";
    private final SpinnerNumberModel zProbeDistance;
    private final SpinnerNumberModel zProbeOffset;
    private final JButton  zProbeButton = new JButton(Localization.getString("probe.button"));

    // inside tab
    private SpinnerNumberModel insideXDistanceModel;
    private SpinnerNumberModel insideYDistanceModel;
    private SpinnerNumberModel insideXOffsetModel;
    private SpinnerNumberModel insideYOffsetModel;
    private SpinnerNumberModel insideXPushModel;
    private SpinnerNumberModel insideYPushModel;
    private final JButton measureInside = new JButton(Localization.getString("probe.measure.inside-corner"));

    // outside center tab
    private static final String OUTSIDE_CENTER_TAB = "OCenter";
    private SpinnerNumberModel mocZDistanceModel;
    private SpinnerNumberModel mocAngleModel;
    private SpinnerNumberModel mocXYDistanceModel;
    private SpinnerNumberModel mocOtherSideModel;
    private final JButton measureOutsideCenter = new JButton("Measure outside center"); //RAINY Localization

    // angle tab
    private static final String ANGLE_TAB = "Angle";
    private SpinnerNumberModel angleForwardDistanceModel;
    private SpinnerNumberModel angleStrafeDistanceModel;
    private SpinnerNumberModel angleStartingAngleModel;
    private final JButton measureAngle = new JButton("Measure angle"); //RAINY Localization
    
    // cuts tab
    private static final String CUTS_TAB = "Cut";
    private SpinnerNumberModel cutDiameterModel;
    private SpinnerNumberModel cutLayerThicknessZModel;
    private SpinnerNumberModel cutDepthZModel;
    private SpinnerNumberModel cutFeedRateModel; //RAINY Merge into settings?
    private ButtonModel cutCCWModel;
    private final JButton cutCylinderShellButton = new JButton("Cut cylinder shell"); //RAINY Localization

    // move tab //RAINY Document - Axes are aligned at angle 0, etc.
    private static final String MOVE_TAB = "Move";
    private SpinnerNumberModel moveDistanceModel;
    private SpinnerNumberModel moveAddAngleModel;
    private final JButton moveXPlusButton = new JButton("X+"); //RAINY Localization
    private final JButton moveXMinusButton = new JButton("X-"); //RAINY Localization
    private final JButton moveYPlusButton = new JButton("Y+"); //RAINY Localization
    private final JButton moveYMinusButton = new JButton("Y-"); //RAINY Localization
    
    // settings
    private JComboBox<WorkCoordinateSystem> settingsWorkCoordinate;
    private JComboBox<String> settingsUnits;
    private SpinnerNumberModel settingsProbeDiameter;
    private SpinnerNumberModel settingsFastFindRate;
    private SpinnerNumberModel settingsSlowMeasureRate;
    private SpinnerNumberModel settingsRetractAmount;

    private static final String SETTINGS_TAB = "Settings";

    private final JTabbedPane jtp = new JTabbedPane(JTabbedPane.LEFT);

    private final ProbeService ps2;
    private final BackendAPI backend;

    @OnStart
    public static class Localizer extends TopComponentLocalizer {
      public Localizer() {
        super(ProbeCategory, ProbeActionId, ProbeTitle);
      }
    }

    protected class ProbeSettings {
        private double xyzXDistance;
        private double xyzYDistance;
        private double xyzZDistance;
        private double xyzXOffset;
        private double xyzYOffset;
        private double xyzZOffset;
        private double xyzXPush;
        private double xyzYPush;
        private double xyzZPush;

        private double outsideXDistance;
        private double outsideYDistance;
        private double outsideXOffset;
        private double outsideYOffset;
        private double outsideXPush;
        private double outsideYPush;

        private double zDistance;
        private double zOffset;

        private double insideXDistance;
        private double insideYDistance;
        private double insideXOffset;
        private double insideYOffset;
        private double insideXPush;
        private double insideYPush;

        private double mocZDistance;
        private double mocAngle;
        private double mocXYDistance;
        private double mocOtherSide;

        private double angleForwardDistance;
        private double angleStrafeDistance;
        private double angleStartingAngle;
        
        public double cutDiameter;
        public double cutLayerThicknessZ;
        public double cutDepthZ;
        public double cutFeedRate; //THINK Separate parameter for Z/XY?
        public boolean cutCCW;

        public double moveDistance;
        public double moveAddAngle;
        
        private int settingsWorkCoordinateIdx;
        private int settingsUnitsIdx;
        private double settingsProbeDiameter;
        private double settingsFastFindRate;
        private double settingsSlowMeasureRate;
        private double settingsRetractAmount;

        private int selectedTabIdx;
    }

    public ProbeTopComponent() {
        setName(ProbeTitle);
        setToolTipText(ProbeTooltip);

        backend = CentralLookup.getDefault().lookup(BackendAPI.class);
        backend.addUGSEventListener(this);

        ps2 = new ProbeService(backend);

        double largeSpinner = 1000000;

        // XYZ TAB
        xyzXDistanceModel = new SpinnerNumberModel(10., -largeSpinner, largeSpinner, 0.1);
        xyzYDistanceModel = new SpinnerNumberModel(10., -largeSpinner, largeSpinner, 0.1);
        xyzZDistanceModel = new SpinnerNumberModel(10., -largeSpinner, largeSpinner, 0.1);
        xyzXOffsetModel = new SpinnerNumberModel(2., -largeSpinner, largeSpinner, 0.1);
        xyzYOffsetModel = new SpinnerNumberModel(2., -largeSpinner, largeSpinner, 0.1);
        xyzZOffsetModel = new SpinnerNumberModel(2., -largeSpinner, largeSpinner, 0.1);
        xyzXPushModel = new SpinnerNumberModel(2., -largeSpinner, largeSpinner, 0.1);
        xyzYPushModel = new SpinnerNumberModel(2., -largeSpinner, largeSpinner, 0.1);
        xyzZPushModel = new SpinnerNumberModel(2., -largeSpinner, largeSpinner, 0.1);

        // OUTSIDE TAB
        outsideXDistanceModel = new SpinnerNumberModel(10., -largeSpinner, largeSpinner, 0.1);
        outsideYDistanceModel = new SpinnerNumberModel(10., -largeSpinner, largeSpinner, 0.1);
        outsideXOffsetModel = new SpinnerNumberModel(2., -largeSpinner, largeSpinner, 0.1);
        outsideYOffsetModel = new SpinnerNumberModel(2., -largeSpinner, largeSpinner, 0.1);
        outsideXPushModel = new SpinnerNumberModel(2., -largeSpinner, largeSpinner, 0.1);
        outsideYPushModel = new SpinnerNumberModel(2., -largeSpinner, largeSpinner, 0.1);

        // Z PROBE TAB
        zProbeDistance = new SpinnerNumberModel(10., -largeSpinner, largeSpinner, 0.1);
        zProbeOffset = new SpinnerNumberModel(10., -largeSpinner, largeSpinner, 0.1);

        // INSIDE TAB
        insideXDistanceModel = new SpinnerNumberModel(10., -largeSpinner, largeSpinner, 0.1);
        insideYDistanceModel = new SpinnerNumberModel(10., -largeSpinner, largeSpinner, 0.1);
        insideXOffsetModel = new SpinnerNumberModel(2., -largeSpinner, largeSpinner, 0.1);
        insideYOffsetModel = new SpinnerNumberModel(2., -largeSpinner, largeSpinner, 0.1);
        insideXPushModel = new SpinnerNumberModel(2., -largeSpinner, largeSpinner, 0.1);
        insideYPushModel = new SpinnerNumberModel(2., -largeSpinner, largeSpinner, 0.1);

        // OUTSIDE MEASURE CENTER TAB
        mocZDistanceModel = new SpinnerNumberModel(-10., -largeSpinner, largeSpinner, 0.1);
        mocAngleModel = new SpinnerNumberModel(90., -largeSpinner, largeSpinner, 0.1); //THINK Restrict to 0-360?
        mocXYDistanceModel = new SpinnerNumberModel(50., -largeSpinner, largeSpinner, 0.1);
        mocOtherSideModel = new SpinnerNumberModel(100., -largeSpinner, largeSpinner, 0.1);

        // ANGLE TAB
        angleForwardDistanceModel = new SpinnerNumberModel(20., -largeSpinner, largeSpinner, 0.1);
        angleStrafeDistanceModel = new SpinnerNumberModel(30., -largeSpinner, largeSpinner, 0.1);
        angleStartingAngleModel = new SpinnerNumberModel(0., -largeSpinner, largeSpinner, 0.1); //THINK Restrict to 0-360?
        
        // CUTS TAB
        cutDiameterModel = new SpinnerNumberModel(20., -largeSpinner, largeSpinner, 0.1);
        cutLayerThicknessZModel = new SpinnerNumberModel(2., -largeSpinner, largeSpinner, 0.1);
        cutDepthZModel = new SpinnerNumberModel(10., -largeSpinner, largeSpinner, 0.1);
        cutFeedRateModel = new SpinnerNumberModel(30., 1, largeSpinner, 0.1);
        cutCCWModel = new JToggleButton.ToggleButtonModel();
        cutCCWModel.setSelected(true);

        // MOVE TAB
        moveDistanceModel = new SpinnerNumberModel(10., -largeSpinner, largeSpinner, 0.1);
        moveAddAngleModel = new SpinnerNumberModel(0., -largeSpinner, largeSpinner, 0.1); //DITTO
        
        // SETTINGS TAB
        settingsWorkCoordinate = new JComboBox<>(new WorkCoordinateSystem[]{G54, G55, G56, G57, G58, G59});
        settingsUnits = new JComboBox<>(new String[]{
            Localization.getString("mainWindow.swing.mmRadioButton"),
            Localization.getString("mainWindow.swing.inchRadioButton")
        });
        settingsProbeDiameter = new SpinnerNumberModel(10., 0., largeSpinner, 0.1);
        settingsFastFindRate = new SpinnerNumberModel(250., 1, largeSpinner, 1.);
        settingsSlowMeasureRate = new SpinnerNumberModel(100., 1, largeSpinner, 1.);
        settingsRetractAmount = new SpinnerNumberModel(1, 0.1, largeSpinner, 0.1);

        measureXYZ.addActionListener(e -> {
                ProbeParameters pc = new ProbeParameters(
                        getDouble(settingsProbeDiameter), backend.getMachinePosition(),
                        getDouble(xyzXDistanceModel), getDouble(xyzYDistanceModel), getDouble(xyzZDistanceModel),
                        getDouble(xyzXOffsetModel), getDouble(xyzYOffsetModel), getDouble(xyzZOffsetModel),
                        getDouble(xyzXPushModel), getDouble(xyzYPushModel), getDouble(xyzZPushModel), //THINK Final push not used
                        0., 0., 0.,
                        0., 0., 0., 0., false,
                        getDouble(settingsFastFindRate), getDouble(settingsSlowMeasureRate),
                        getDouble(settingsRetractAmount), getUnits(), get(settingsWorkCoordinate),
                        null
                );
                this.cornerRenderable.setContext(pc, backend.getWorkPosition(), backend.getMachinePosition());
                ps2.performXYZProbe(pc);
            });

        measureOutside.addActionListener(e -> {
                ProbeParameters pc = new ProbeParameters(
                        getDouble(settingsProbeDiameter), backend.getMachinePosition(),
                        getDouble(outsideXDistanceModel), getDouble(outsideYDistanceModel), 0.,
                        getDouble(outsideXOffsetModel), getDouble(outsideYOffsetModel), 0.,
                        getDouble(outsideXPushModel), getDouble(outsideYPushModel), 0., //THINK Final push not used
                        0., 0., 0.,
                        0., 0., 0., 0., false,
                        getDouble(settingsFastFindRate), getDouble(settingsSlowMeasureRate),
                        getDouble(settingsRetractAmount), getUnits(), get(settingsWorkCoordinate),
                        null
                );
                this.cornerRenderable.setContext(pc, backend.getWorkPosition(), backend.getMachinePosition());
                ps2.performOutsideCornerProbe(pc);
            });

        /*
        measureInside.addActionListener((e) -> {
            ProbeContext pc = new ProbeContext(
                1, backend.getMachinePosition(),
                get(insideXDistanceModel), get(insideYDistanceModel), 100., 1);
                ps2.performInsideCornerProbe(pc);
            });
        */

        zProbeButton.addActionListener(e -> {
                ProbeParameters pc = new ProbeParameters(
                        getDouble(settingsProbeDiameter), backend.getMachinePosition(),
                        0., 0., getDouble(zProbeDistance),
                        0., 0., getDouble(zProbeOffset),
                        0., 0., 0.,
                        0., 0., 0.,
                        0., 0., 0., 0., false,
                        getDouble(settingsFastFindRate), getDouble(settingsSlowMeasureRate),
                        getDouble(settingsRetractAmount), getUnits(), get(settingsWorkCoordinate),
                        null
                );
                this.zRenderable.setStart(backend.getWorkPosition());
                ps2.performZProbe(pc);
            });

        measureOutsideCenter.addActionListener(e -> {
                ProbeParameters pc = new ProbeParameters(
                        getDouble(settingsProbeDiameter), backend.getMachinePosition(),
                        0., 0., getDouble(mocZDistanceModel),
                        0., 0., 0., //DUMMY Offset
                        0., 0., 0.,
                        getDouble(mocAngleModel), getDouble(mocXYDistanceModel), getDouble(mocOtherSideModel),
                        0., 0., 0., 0., false,
                        getDouble(settingsFastFindRate), getDouble(settingsSlowMeasureRate),
                        getDouble(settingsRetractAmount), getUnits(), get(settingsWorkCoordinate),
                        null
                );
                //DUMMY renderable
                //this.zRenderable.setStart(backend.getWorkPosition());
                ps2.performOutsideCenter(pc);
            });

        measureAngle.addActionListener(e -> {
                ProbeParameters pc = new ProbeParameters(
                        getDouble(settingsProbeDiameter), backend.getMachinePosition(),
                        0., 0., 0.,
                        0., 0., 0., //DUMMY Offset
                        0., 0., 0.,
                        getDouble(angleStartingAngleModel), getDouble(angleForwardDistanceModel), getDouble(angleStrafeDistanceModel),
                        0., 0., 0., 0., false,
                        getDouble(settingsFastFindRate), getDouble(settingsSlowMeasureRate),
                        getDouble(settingsRetractAmount), getUnits(), get(settingsWorkCoordinate),
                        (angle) -> {
                            SwingUtilities.invokeLater(() -> {
                                angleStartingAngleModel.setValue(angle);
                            });
                        }
                );
                //DUMMY renderable
                //this.zRenderable.setStart(backend.getWorkPosition());
                ps2.performMeasureAngle(pc);
            });
        
        cutCylinderShellButton.addActionListener(e -> {
                ProbeParameters pc = new ProbeParameters(
                        getDouble(settingsProbeDiameter), backend.getMachinePosition(),
                        0., 0., getDouble(mocZDistanceModel),
                        0., 0., 0., //DUMMY Offset
                        0., 0., 0.,
                        0., 0., 0.,
                        getDouble(cutDiameterModel), getDouble(cutLayerThicknessZModel), getDouble(cutDepthZModel), getDouble(cutFeedRateModel), cutCCWModel.isSelected(),
                        getDouble(settingsFastFindRate), getDouble(settingsSlowMeasureRate),
                        getDouble(settingsRetractAmount), getUnits(), get(settingsWorkCoordinate),
                        null
                );
                //DUMMY renderable //RAINY Show preview on hover over button?
                //this.zRenderable.setStart(backend.getWorkPosition());
                ps2.performCutCylinderShell(pc);
            });

        { // Move
            // Everything just uses performMoveXPlus with different angles
            
            moveXMinusButton.addActionListener(e -> {
                    ProbeParameters pc = new ProbeParameters(
                            getDouble(settingsProbeDiameter), backend.getMachinePosition(),
                            -getDouble(moveDistanceModel), 0., 0.,
                            0., 0., 0., //DUMMY Offset
                            0., 0., 0.,
                            getDouble(moveAddAngleModel), 0., 0.,
                            0., 0., 0., 0., false,
                            getDouble(settingsFastFindRate), getDouble(settingsSlowMeasureRate),
                            getDouble(settingsRetractAmount), getUnits(), get(settingsWorkCoordinate),
                            null
                    );
                    //DUMMY renderable //RAINY Show preview on hover over button?
                    //this.zRenderable.setStart(backend.getWorkPosition());
                    ps2.performMoveXPlus(pc);
                });
            moveXPlusButton.addActionListener(e -> {
                    ProbeParameters pc = new ProbeParameters(
                            getDouble(settingsProbeDiameter), backend.getMachinePosition(),
                            getDouble(moveDistanceModel), 0., 0.,
                            0., 0., 0., //DUMMY Offset
                            0., 0., 0.,
                            getDouble(moveAddAngleModel), 0., 0.,
                            0., 0., 0., 0., false,
                            getDouble(settingsFastFindRate), getDouble(settingsSlowMeasureRate),
                            getDouble(settingsRetractAmount), getUnits(), get(settingsWorkCoordinate),
                            null
                    );
                    //DUMMY renderable //RAINY Show preview on hover over button?
                    //this.zRenderable.setStart(backend.getWorkPosition());
                    ps2.performMoveXPlus(pc);
                });
            moveYMinusButton.addActionListener(e -> {
                    ProbeParameters pc = new ProbeParameters(
                            getDouble(settingsProbeDiameter), backend.getMachinePosition(),
                            -getDouble(moveDistanceModel), 0., 0.,
                            0., 0., 0., //DUMMY Offset
                            0., 0., 0.,
                            90+getDouble(moveAddAngleModel), 0., 0.,
                            0., 0., 0., 0., false,
                            getDouble(settingsFastFindRate), getDouble(settingsSlowMeasureRate),
                            getDouble(settingsRetractAmount), getUnits(), get(settingsWorkCoordinate),
                            null
                    );
                    //DUMMY renderable //RAINY Show preview on hover over button?
                    //this.zRenderable.setStart(backend.getWorkPosition());
                    ps2.performMoveXPlus(pc);
                });
            moveYPlusButton.addActionListener(e -> {
                    ProbeParameters pc = new ProbeParameters(
                            getDouble(settingsProbeDiameter), backend.getMachinePosition(),
                            getDouble(moveDistanceModel), 0., 0.,
                            0., 0., 0., //DUMMY Offset
                            0., 0., 0.,
                            90+getDouble(moveAddAngleModel), 0., 0.,
                            0., 0., 0., 0., false,
                            getDouble(settingsFastFindRate), getDouble(settingsSlowMeasureRate),
                            getDouble(settingsRetractAmount), getUnits(), get(settingsWorkCoordinate),
                            null
                    );
                    //DUMMY renderable //RAINY Show preview on hover over button?
                    //this.zRenderable.setStart(backend.getWorkPosition());
                    ps2.performMoveXPlus(pc);
                });
        }
        
        initComponents();
        updateControls();

        // Listeners...
        this.xyzXDistanceModel.addChangeListener(l -> controlChangeListener());
        this.xyzYDistanceModel.addChangeListener(l -> controlChangeListener());
        this.xyzZDistanceModel.addChangeListener(l -> controlChangeListener());
        this.outsideXDistanceModel.addChangeListener(l -> controlChangeListener());
        this.outsideYDistanceModel.addChangeListener(l -> controlChangeListener());
        this.insideXDistanceModel.addChangeListener(l -> controlChangeListener());
        this.insideYDistanceModel.addChangeListener(l -> controlChangeListener());

        this.zProbeDistance.addChangeListener(l -> controlChangeListener());
        this.zProbeOffset.addChangeListener(l -> controlChangeListener());

        this.xyzXOffsetModel.addChangeListener(l -> controlChangeListener());
        this.xyzYOffsetModel.addChangeListener(l -> controlChangeListener());
        this.xyzZOffsetModel.addChangeListener(l -> controlChangeListener());
        this.xyzXPushModel.addChangeListener(l -> controlChangeListener());
        this.xyzYPushModel.addChangeListener(l -> controlChangeListener());
        this.xyzZPushModel.addChangeListener(l -> controlChangeListener());
        this.outsideXOffsetModel.addChangeListener(l -> controlChangeListener());
        this.outsideYOffsetModel.addChangeListener(l -> controlChangeListener());
        this.outsideXPushModel.addChangeListener(l -> controlChangeListener());
        this.outsideYPushModel.addChangeListener(l -> controlChangeListener());
        this.insideXOffsetModel.addChangeListener(l -> controlChangeListener());
        this.insideYOffsetModel.addChangeListener(l -> controlChangeListener());
        this.insideXPushModel.addChangeListener(l -> controlChangeListener());
        this.insideYPushModel.addChangeListener(l -> controlChangeListener());

        this.mocZDistanceModel.addChangeListener(l -> controlChangeListener());
        this.mocAngleModel.addChangeListener(l -> controlChangeListener());
        this.mocXYDistanceModel.addChangeListener(l -> controlChangeListener());
        this.mocOtherSideModel.addChangeListener(l -> controlChangeListener());

        this.angleForwardDistanceModel.addChangeListener(l -> controlChangeListener());
        this.angleStrafeDistanceModel.addChangeListener(l -> controlChangeListener());
        this.angleStartingAngleModel.addChangeListener(l -> controlChangeListener());
        
        this.cutDiameterModel.addChangeListener(l -> controlChangeListener());
        this.cutLayerThicknessZModel.addChangeListener(l -> controlChangeListener());
        this.cutDepthZModel.addChangeListener(l -> controlChangeListener());
        this.cutFeedRateModel.addChangeListener(l -> controlChangeListener());
        this.cutCCWModel.addChangeListener(l -> controlChangeListener());
        
        this.moveDistanceModel.addChangeListener(l -> controlChangeListener());
        this.moveAddAngleModel.addChangeListener(l -> controlChangeListener());

        this.settingsWorkCoordinate.addActionListener(l -> controlChangeListener());
        this.settingsUnits.addActionListener(l -> controlChangeListener());
        this.settingsProbeDiameter.addChangeListener(l -> controlChangeListener());
        this.settingsFastFindRate.addChangeListener(l -> controlChangeListener());
        this.settingsSlowMeasureRate.addChangeListener(l -> controlChangeListener());
        this.settingsRetractAmount.addChangeListener(l -> controlChangeListener());

        this.jtp.addChangeListener(l -> controlChangeListener());
    }

    private void controlChangeListener() {
        Renderable before = active;
        //switch (this.jtp.getTabComponentAt(this.jtp.getSelectedIndex()).getName()) {
        switch (this.jtp.getTitleAt(this.jtp.getSelectedIndex())) {
            case XYZ_TAB:
                // TODO: XYZ Renderable
                active = cornerRenderable;
                cornerRenderable.updateSpacing(
                        getDouble(xyzXDistanceModel),
                        getDouble(xyzYDistanceModel),
                        getDouble(xyzZDistanceModel),
                        getDouble(xyzXOffsetModel),
                        getDouble(xyzYOffsetModel),
                        getDouble(xyzZOffsetModel));
                break;
            case OUTSIDE_TAB:
                active = cornerRenderable;
                cornerRenderable.updateSpacing(
                        getDouble(outsideXDistanceModel),
                        getDouble(outsideYDistanceModel),
                        0,
                        getDouble(outsideXOffsetModel),
                        getDouble(outsideYOffsetModel),
                        0);
                break;
            case Z_TAB:
                active = zRenderable;
                zRenderable.updateSpacing(getDouble(zProbeDistance), getDouble(zProbeOffset));
                break;
            case OUTSIDE_CENTER_TAB:
                //DUMMY
//                active = cornerRenderable;
//                cornerRenderable.updateSpacing(
//                        getDouble(outsideXDistanceModel),
//                        getDouble(outsideYDistanceModel),
//                        0,
//                        getDouble(outsideXOffsetModel),
//                        getDouble(outsideYOffsetModel),
//                        0);
                break;
            case ANGLE_TAB:
                //DUMMY
//                active = cornerRenderable;
//                cornerRenderable.updateSpacing(
//                        getDouble(outsideXDistanceModel),
//                        getDouble(outsideYDistanceModel),
//                        0,
//                        getDouble(outsideXOffsetModel),
//                        getDouble(outsideYOffsetModel),
//                        0);
                break;
            case CUTS_TAB:
                //DUMMY
//                active = cornerRenderable;
//                cornerRenderable.updateSpacing(
//                        getDouble(outsideXDistanceModel),
//                        getDouble(outsideYDistanceModel),
//                        0,
//                        getDouble(outsideXOffsetModel),
//                        getDouble(outsideYOffsetModel),
//                        0);
                break;
            case MOVE_TAB:
                //DUMMY
//                active = cornerRenderable;
//                cornerRenderable.updateSpacing(
//                        getDouble(outsideXDistanceModel),
//                        getDouble(outsideYDistanceModel),
//                        0,
//                        getDouble(outsideXOffsetModel),
//                        getDouble(outsideYOffsetModel),
//                        0);
                break;
            case SETTINGS_TAB:
                active = null;
                break;
        }

        if (before != active) {
            RenderableUtils.removeRenderable(before);
            RenderableUtils.registerRenderable(this.active);
        }
    }

    public void updateControls() {
        boolean enabled = backend.isIdle();
        this.measureXYZ.setEnabled(enabled);
        this.measureInside.setEnabled(enabled);
        this.measureOutside.setEnabled(enabled);
        this.measureOutsideCenter.setEnabled(enabled);
        this.zProbeButton.setEnabled(enabled);
        this.measureAngle.setEnabled(enabled);
        this.cutCylinderShellButton.setEnabled(enabled);
        this.moveXMinusButton.setEnabled(enabled);
        this.moveXPlusButton.setEnabled(enabled);
        this.moveYMinusButton.setEnabled(enabled);
        this.moveYPlusButton.setEnabled(enabled);
    }

    @Override
    public void UGSEvent(UGSEvent evt) {
        if (evt instanceof ControllerStateEvent) {
            updateControls();
        }
    }

    private Units getUnits() {
        return this.settingsUnits.getSelectedIndex() == 0 ? Units.MM : Units.INCH;
    }

    // Helper since getSelectedItem doesn't use generics.
    private static WorkCoordinateSystem get(JComboBox<WorkCoordinateSystem> wcsCombo) {
        return wcsCombo.getItemAt(wcsCombo.getSelectedIndex());
    }

    private void initComponents() {
        //RAINY Add tooltips - especially for "push"
            
        // XYZ TAB
        JPanel xyz = new JPanel(new MigLayout("flowy, wrap 3"));
        xyz.add(new JLabel(X_DISTANCE));
        xyz.add(new JLabel(Y_DISTANCE));
        xyz.add(new JLabel(Z_DISTANCE));
        xyz.add(new JSpinner(xyzXDistanceModel), "growx");
        xyz.add(new JSpinner(xyzYDistanceModel), "growx");
        xyz.add(new JSpinner(xyzZDistanceModel), "growx");

        xyz.add(new JLabel(X_OFFSET));
        xyz.add(new JLabel(Y_OFFSET));
        xyz.add(new JLabel(Z_OFFSET));
        xyz.add(new JSpinner(xyzXOffsetModel), "growx");
        xyz.add(new JSpinner(xyzYOffsetModel), "growx");
        xyz.add(new JSpinner(xyzZOffsetModel), "growx");

        xyz.add(new JLabel(X_PUSH));
        xyz.add(new JLabel(Y_PUSH));
        xyz.add(new JLabel(Z_PUSH));
        xyz.add(new JSpinner(xyzXPushModel), "growx");
        xyz.add(new JSpinner(xyzYPushModel), "growx");
        xyz.add(new JSpinner(xyzZPushModel), "growx");
        
        xyz.add(measureXYZ, "spanx 2, spany 3, growx, growy");

        // TODO: INSIDE Probe
        JPanel inside = new JPanel(new MigLayout("flowy, wrap 2"));
        inside.add(new JLabel(X_DISTANCE));
        inside.add(new JLabel(Y_DISTANCE));
        inside.add(new JSpinner(insideXDistanceModel), "growx");
        inside.add(new JSpinner(insideYDistanceModel), "growx");

        inside.add(new JLabel(X_OFFSET));
        inside.add(new JLabel(Y_OFFSET));
        inside.add(new JSpinner(insideXOffsetModel), "growx");
        inside.add(new JSpinner(insideYOffsetModel), "growx");

        inside.add(new JLabel(X_PUSH));
        inside.add(new JLabel(Y_PUSH));
        inside.add(new JSpinner(insideXPushModel), "growx");
        inside.add(new JSpinner(insideYPushModel), "growx");
        
        inside.add(measureInside, "spanx 2, spany 2, growx, growy");

        // OUTSIDE TAB
        JPanel outside = new JPanel(new MigLayout("flowy, wrap 2"));
        outside.add(new JLabel(X_DISTANCE));
        outside.add(new JLabel(Y_DISTANCE));
        outside.add(new JSpinner(outsideXDistanceModel), "growx");
        outside.add(new JSpinner(outsideYDistanceModel), "growx");

        outside.add(new JLabel(X_OFFSET));
        outside.add(new JLabel(Y_OFFSET));
        outside.add(new JSpinner(outsideXOffsetModel), "growx");
        outside.add(new JSpinner(outsideYOffsetModel), "growx");

        outside.add(new JLabel(X_PUSH));
        outside.add(new JLabel(Y_PUSH));
        outside.add(new JSpinner(outsideXPushModel), "growx");
        outside.add(new JSpinner(outsideYPushModel), "growx");
        
        outside.add(measureOutside, "spanx 2, spany 2, growx, growy");

        // Z PROBE TAB
        JPanel z = new JPanel(new MigLayout("wrap 4"));
        z.add(new JLabel(Z_OFFSET));
        z.add(new JSpinner(this.zProbeOffset), "growx");

        z.add(this.zProbeButton, "spanx 2, spany 2, growx, growy");

        z.add(new JLabel(Z_DISTANCE));
        z.add(new JSpinner(this.zProbeDistance), "growx");

        // OUTSIDE CENTER TAB
        JPanel outsideCenter = new JPanel(new MigLayout("flowy, wrap 2"));
        //RAINY Localization?
        outsideCenter.add(new JLabel("Z Probe distance"));
        outsideCenter.add(new JLabel("XY Probe distance"));
        outsideCenter.add(new JSpinner(mocZDistanceModel), "growx");
        outsideCenter.add(new JSpinner(mocXYDistanceModel), "growx");

        outsideCenter.add(new JLabel("Angle")); //RAINY Tooltip explaining degrees, 0=X+, ccw
        outsideCenter.add(new JLabel("Other side"));
        outsideCenter.add(new JSpinner(mocAngleModel), "growx");
        outsideCenter.add(new JSpinner(mocOtherSideModel), "growx");

        outsideCenter.add(measureOutsideCenter, "spanx 2, spany 2, growx, growy");

        // ANGLE TAB
        JPanel angle = new JPanel(new MigLayout("flowy, wrap 3"));
        //RAINY Localization?
        angle.add(new JLabel("Forward distance"));
        angle.add(new JLabel("Sideways distance"));
        angle.add(new JLabel("Starting angle")); //RAINY Tooltip explaining degrees, 0=X+, ccw
        angle.add(new JSpinner(angleForwardDistanceModel), "growx");
        angle.add(new JSpinner(angleStrafeDistanceModel), "growx");
        angle.add(new JSpinner(angleStartingAngleModel), "growx");

        angle.add(measureAngle, "spanx 2, spany 2, growx, growy");
        
        // CUTS TAB
        JPanel cuts = new JPanel(new MigLayout("flowy, wrap 5"));
        //RAINY Localization?
        cuts.add(new JLabel("Outer diameter")); //THINK Ooh.  You might be interested in INNER diameter.
        cuts.add(new JLabel("Layer thickness Z"));
        cuts.add(new JLabel("Depth Z")); //RAINY Tooltip explaining degrees, 0=X+, ccw
        cuts.add(new JLabel("Cut feed rate"));
        JCheckBox cbCcw = new JCheckBox("Cut CCW");
        cbCcw.setModel(cutCCWModel);
        cuts.add(cbCcw);
        
        cuts.add(new JSpinner(cutDiameterModel), "growx");
        cuts.add(new JSpinner(cutLayerThicknessZModel), "growx");
        cuts.add(new JSpinner(cutDepthZModel), "growx");
        cuts.add(new JSpinner(cutFeedRateModel), "growx");
        cuts.add(new JLabel(""));

        cuts.add(cutCylinderShellButton, "spanx 2, spany 1, growx, growy");

        // MOVE TAB
        JPanel move = new JPanel(new MigLayout("flowy, wrap 3"));
        //RAINY Localization?
        move.add(new JLabel("Distance"));
        move.add(new JLabel("Add angle")); //RAINY Tooltip explaining degrees, 0=X+, ccw
        move.add(new JLabel(""));
        move.add(new JSpinner(moveDistanceModel), "growx");
        move.add(new JSpinner(moveAddAngleModel), "growx");
        move.add(new JLabel(""));
        //THINK Mention feedrate?

        move.add(new JButton(), "spanx 1, spany 1, growx, growy");
        move.add(moveXMinusButton, "spanx 1, spany 1, growx, growy");
        move.add(new JButton(), "spanx 1, spany 1, growx, growy");
        move.add(moveYPlusButton, "spanx 1, spany 1, growx, growy");
        move.add(new JButton(), "spanx 1, spany 1, growx, growy");
        move.add(moveYMinusButton, "spanx 1, spany 1, growx, growy");
        move.add(new JButton(), "spanx 1, spany 1, growx, growy");
        move.add(moveXPlusButton, "spanx 1, spany 1, growx, growy");
        move.add(new JButton(), "spanx 1, spany 1, growx, growy");
        
        // SETTINGS TAB
        JPanel settings = new JPanel(new MigLayout("wrap 6"));
        settings.add(new JLabel(Localization.getString("gcode.setting.units") + ":"), "al right");
        settings.add(settingsUnits, "growx");

        settings.add(new JLabel(Localization.getString("gcode.setting.endmill-diameter") + ":"), "al right");
        settings.add(new JSpinner(settingsProbeDiameter), "growx");

        settings.add(new JLabel(Localization.getString("probe.find-rate") + ":"), "al right");
        settings.add(new JSpinner(settingsFastFindRate), "growx");

        settings.add(new JLabel("Work Coordinates:"), "al right");
        settings.add(settingsWorkCoordinate, "growx");

        settings.add(new JLabel(Localization.getString("probe.measure-rate") + ":"), "al right");
        settings.add(new JSpinner(settingsSlowMeasureRate), "growx");

        settings.add(new JLabel(Localization.getString("probe.retract-amount") + ":"), "al right");
        settings.add(new JSpinner(settingsRetractAmount), "growx");

        jtp.add(XYZ_TAB, xyz);
        jtp.add(OUTSIDE_TAB, outside);
        jtp.add(Z_TAB, z);
        //jtp.add("inside", inside);
        jtp.add(OUTSIDE_CENTER_TAB, outsideCenter);
        jtp.add(ANGLE_TAB, angle);
        jtp.add(CUTS_TAB, cuts);
        jtp.add(MOVE_TAB, move);
        jtp.add(SETTINGS_TAB, settings);

        this.setLayout(new BorderLayout());
        this.add(jtp);
    }


    @Override
    public void componentOpened() {
        controlChangeListener();

        // Cleanup after renamed preferred ID.
        String id = WindowManager.getDefault().findTopComponentID(this);
        if (!StringUtils.equals(id, preferredId)) {
          this.close();
        }
    }

    @Override
    public void componentClosed() {
        if (this.active != null) {
            RenderableUtils.removeRenderable(this.active);
        }
    }

    public void writeProperties(java.util.Properties p) {
        // better to version settings since initial version as advocated at
        // http://wiki.apidesign.org/wiki/PropertyFiles
        p.setProperty("version", "1.0");

        ProbeSettings ps = new ProbeSettings();
        ps.xyzXDistance = getDouble(this.xyzXDistanceModel);
        ps.xyzYDistance = getDouble(xyzYDistanceModel);
        ps.xyzZDistance = getDouble(xyzZDistanceModel);
        ps.xyzXOffset = getDouble(xyzXOffsetModel);
        ps.xyzYOffset = getDouble(xyzYOffsetModel);
        ps.xyzZOffset = getDouble(xyzZOffsetModel);
        ps.xyzXPush = getDouble(xyzXPushModel);
        ps.xyzYPush = getDouble(xyzYPushModel);
        ps.xyzZPush = getDouble(xyzZPushModel);

        ps.outsideXDistance = getDouble(this.outsideXDistanceModel);
        ps.outsideYDistance = getDouble(outsideYDistanceModel);
        ps.outsideXOffset = getDouble(outsideXOffsetModel);
        ps.outsideYOffset = getDouble(outsideYOffsetModel);
        ps.outsideXPush = getDouble(outsideXPushModel);
        ps.outsideYPush = getDouble(outsideYPushModel);

        ps.zDistance = getDouble(zProbeDistance);
        ps.zOffset = getDouble(zProbeOffset);

        ps.insideXDistance = getDouble(this.insideXDistanceModel);
        ps.insideYDistance = getDouble(insideYDistanceModel);
        ps.insideXOffset = getDouble(insideXOffsetModel);
        ps.insideYOffset = getDouble(insideYOffsetModel);
        ps.insideXPush = getDouble(insideXPushModel);
        ps.insideYPush = getDouble(insideYPushModel);

        ps.mocZDistance = getDouble(mocZDistanceModel);
        ps.mocAngle = getDouble(mocAngleModel);
        ps.mocXYDistance = getDouble(mocXYDistanceModel);
        ps.mocOtherSide = getDouble(mocOtherSideModel);

        ps.angleForwardDistance = getDouble(angleForwardDistanceModel);
        ps.angleStrafeDistance = getDouble(angleStrafeDistanceModel);
        ps.angleStartingAngle = getDouble(angleStartingAngleModel);
        
        ps.cutDiameter = getDouble(cutDiameterModel);
        ps.cutLayerThicknessZ = getDouble(cutLayerThicknessZModel);
        ps.cutDepthZ = getDouble(cutDepthZModel);
        ps.cutFeedRate = getDouble(cutFeedRateModel);
        ps.cutCCW = cutCCWModel.isSelected();
        
        ps.moveDistance = getDouble(moveDistanceModel);
        ps.moveAddAngle = getDouble(moveAddAngleModel);

        ps.settingsWorkCoordinateIdx = settingsWorkCoordinate.getSelectedIndex();
        ps.settingsUnitsIdx = settingsUnits.getSelectedIndex();
        ps.settingsProbeDiameter = getDouble(settingsProbeDiameter);
        ps.settingsFastFindRate = getDouble(settingsFastFindRate);
        ps.settingsSlowMeasureRate = getDouble(settingsSlowMeasureRate);
        ps.settingsRetractAmount = getDouble(settingsRetractAmount);

        ps.selectedTabIdx = this.jtp.getSelectedIndex();

        p.setProperty("json_data", new Gson().toJson(ps));
    }

    public void readProperties(java.util.Properties p) {
        String version = p.getProperty("version");

        String jsonData = p.getProperty("json_data");
        if (jsonData == null) return;

        ProbeSettings ps = new Gson().fromJson(jsonData, ProbeSettings.class);
        xyzXDistanceModel.setValue(ps.xyzXDistance);
        xyzYDistanceModel.setValue(ps.xyzYDistance);
        xyzZDistanceModel.setValue(ps.xyzZDistance);
        xyzXOffsetModel.setValue(ps.xyzXOffset);
        xyzYOffsetModel.setValue(ps.xyzYOffset);
        xyzZOffsetModel.setValue(ps.xyzZOffset);
        xyzXPushModel.setValue(ps.xyzXPush);
        xyzYPushModel.setValue(ps.xyzYPush);
        xyzZPushModel.setValue(ps.xyzZPush);

        outsideXDistanceModel.setValue(ps.outsideXDistance);
        outsideYDistanceModel.setValue(ps.outsideYDistance);
        outsideXOffsetModel.setValue(ps.outsideXOffset);
        outsideYOffsetModel.setValue(ps.outsideYOffset);
        outsideXPushModel.setValue(ps.outsideXPush);
        outsideYPushModel.setValue(ps.outsideYPush);

        zProbeDistance.setValue(ps.zDistance);
        zProbeOffset.setValue(ps.zOffset);

        insideXDistanceModel.setValue(ps.insideXDistance);
        insideYDistanceModel.setValue(ps.insideYDistance);
        insideXOffsetModel.setValue(ps.insideXOffset);
        insideYOffsetModel.setValue(ps.insideYOffset);
        insideXPushModel.setValue(ps.insideXPush);
        insideYPushModel.setValue(ps.insideYPush);

        mocZDistanceModel.setValue(ps.mocZDistance);
        mocAngleModel.setValue(ps.mocAngle);
        mocXYDistanceModel.setValue(ps.mocXYDistance);
        mocOtherSideModel.setValue(ps.mocOtherSide);

        angleForwardDistanceModel.setValue(ps.angleForwardDistance);
        angleStrafeDistanceModel.setValue(ps.angleStrafeDistance);
        angleStartingAngleModel.setValue(ps.angleStartingAngle);
        
        cutDiameterModel.setValue(ps.cutDiameter);
        cutLayerThicknessZModel.setValue(ps.cutLayerThicknessZ);
        cutDepthZModel.setValue(ps.cutDepthZ);
        cutFeedRateModel.setValue(ps.cutFeedRate);
        cutCCWModel.setSelected(ps.cutCCW);

        moveDistanceModel.setValue(ps.moveDistance);
        moveAddAngleModel.setValue(ps.moveAddAngle);

        settingsWorkCoordinate.setSelectedIndex(ps.settingsWorkCoordinateIdx);
        settingsUnits.setSelectedIndex(ps.settingsUnitsIdx);
        settingsProbeDiameter.setValue(ps.settingsProbeDiameter);
        settingsFastFindRate.setValue(ps.settingsFastFindRate);
        settingsSlowMeasureRate.setValue(ps.settingsSlowMeasureRate);
        settingsRetractAmount.setValue(ps.settingsRetractAmount);

        jtp.setSelectedIndex(ps.selectedTabIdx);
    }
}
