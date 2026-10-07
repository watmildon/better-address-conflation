// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.gui;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;

import org.openstreetmap.josm.gui.preferences.DefaultTabPreferenceSetting;
import org.openstreetmap.josm.gui.preferences.PreferenceTabbedPane;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;
import org.openstreetmap.josm.plugins.addressconflation.io.CustomSource;
import org.openstreetmap.josm.plugins.addressconflation.io.FeatureSource;
import org.openstreetmap.josm.tools.Utils;
import org.openstreetmap.josm.spi.preferences.Config;

/** Preferences tab. */
public class AddressConflationPreferences extends DefaultTabPreferenceSetting {
    public static final String PREF_MATCH_DISTANCE = "addressconflation.matchDistance";
    public static final String PREF_AMBIGUITY_RATIO = "addressconflation.ambiguityRatio";
    public static final String PREF_SPLIT_TOLERANCE = "addressconflation.splitTolerance";
    public static final String PREF_OUTBUILDINGS = "addressconflation.outbuildings";
    public static final String PREF_DELETE_SOURCE = "addressconflation.deleteSourceNodes";
    /** Used by the OpenAddresses paths, which have no UI yet; so no checkbox either. */
    public static final String PREF_EXPAND_STREETS = "addressconflation.oa.expandStreets";

    private final JSpinner matchDistance = new JSpinner(new SpinnerNumberModel(30, 1, 1000, 5));
    private final JSpinner ambiguityRatio = new JSpinner(new SpinnerNumberModel(0.75, 0.1, 1.0, 0.05));
    private final JSpinner splitTolerance = new JSpinner(new SpinnerNumberModel(20, 0, 100, 5));
    private final JTextField outbuildings = new JTextField(40);
    private final JCheckBox deleteSource = new JCheckBox(tr("Delete address nodes from the address layer after applying"));
    /** The mapper's own sources, edited here and saved on OK. */
    private final DefaultListModel<CustomSource> sources = new DefaultListModel<>();
    private final JList<CustomSource> sourceList = new JList<>(sources);

    public AddressConflationPreferences() {
        super("address-conflation", tr("Better Address Conflation"), tr("Settings for matching address points to buildings"));
    }

    /** Current preferences as engine settings. */
    public static ConflationSettings fromPreferences() {
        ConflationSettings s = new ConflationSettings();
        s.matchDistanceMeters = Config.getPref().getDouble(PREF_MATCH_DISTANCE, s.matchDistanceMeters);
        s.ambiguityRatio = Config.getPref().getDouble(PREF_AMBIGUITY_RATIO, s.ambiguityRatio);
        s.splitTolerance = Config.getPref().getDouble(PREF_SPLIT_TOLERANCE, s.splitTolerance);
        List<String> ob = Config.getPref().getList(PREF_OUTBUILDINGS, ConflationSettings.DEFAULT_OUTBUILDINGS);
        s.setOutbuildings(ob);
        s.deleteSourceNodes = Config.getPref().getBoolean(PREF_DELETE_SOURCE, true);
        return s;
    }

    public static boolean isExpandStreets() {
        return Config.getPref().getBoolean(PREF_EXPAND_STREETS, true);
    }

    @Override
    public void addGui(PreferenceTabbedPane gui) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.gridx = 0;
        gc.gridy = 0;
        panel.add(new JLabel(tr("Match distance (m):")), gc);
        gc.gridx = 1;
        panel.add(matchDistance, gc);
        gc.gridx = 2;
        gc.weightx = 1;
        panel.add(new JLabel(tr("how far an address point may be from its parcel or building")), gc);
        gc.weightx = 0;
        gc.gridx = 0;
        gc.gridy++;
        panel.add(new JLabel(tr("Ambiguity ratio:")), gc);
        gc.gridx = 1;
        panel.add(ambiguityRatio, gc);
        gc.gridx = 2;
        panel.add(new JLabel(tr("runner-up / primary score at or above this needs review")), gc);
        gc.gridx = 0;
        gc.gridy++;
        panel.add(new JLabel(tr("Split building tolerance (%):")), gc);
        gc.gridx = 1;
        panel.add(splitTolerance, gc);
        gc.gridx = 2;
        panel.add(new JLabel(tr("share of a building that may lie outside its parcel")), gc);
        gc.gridx = 0;
        gc.gridy++;
        panel.add(new JLabel(tr("Outbuilding values:")), gc);
        gc.gridx = 1;
        gc.gridwidth = 2;
        gc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(outbuildings, gc);
        gc.gridx = 0;
        gc.gridy++;
        gc.gridwidth = 3;
        panel.add(deleteSource, gc);
        gc.gridy++;
        gc.insets = new Insets(12, 4, 4, 4);
        panel.add(new JLabel(tr("Custom Sources")), gc);
        gc.insets = new Insets(4, 4, 4, 4);
        gc.gridy++;
        gc.weighty = 1;
        gc.fill = GridBagConstraints.BOTH;
        panel.add(sourcesPanel(), gc);

        matchDistance.setToolTipText(tr("Addresses farther than this from their parcel or any building are left unmatched. Raise it in spread-out rural areas."));
        ambiguityRatio.setToolTipText(tr("When the second-best building scores at least this fraction of the best, the address goes to review instead of being matched."));
        splitTolerance.setToolTipText(tr("With a parcel layer: when more of the building than this lies outside the address''s parcel, "
                + "the match goes to Check, then apply. Raise it where parcels and buildings are offset and slivers cross lot lines."));
        outbuildings.setToolTipText(tr("building=* values ranked low as address targets (garages, sheds...). Separate with commas."));
        deleteSource.setToolTipText(tr("After applying, delete the matched node from the address layer so it is not applied twice"));

        for (CustomSource cs : CustomSource.load()) {
            sources.addElement(cs);
        }
        ConflationSettings s = fromPreferences();
        matchDistance.setValue((int) Math.round(s.matchDistanceMeters));
        ambiguityRatio.setValue(s.ambiguityRatio);
        splitTolerance.setValue((int) Math.round(s.splitTolerance * 100));
        outbuildings.setText(String.join(", ", Config.getPref().getList(PREF_OUTBUILDINGS, ConflationSettings.DEFAULT_OUTBUILDINGS)));
        deleteSource.setSelected(s.deleteSourceNodes);
        createPreferenceTabWithScrollPane(gui, panel);
    }

    private JPanel sourcesPanel() {
        sourceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sourceList.setVisibleRowCount(5);
        sourceList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                CustomSource cs = (CustomSource) value;
                String text = "<html>" + Utils.escapeReservedCharactersHTML(cs.getName()) + " <font color=\"gray\">&mdash; "
                        + CustomSourceEditor.kindLabel(cs.getKind()) + " &middot; "
                        + (cs.getProtocol() == FeatureSource.Protocol.OGC_FEATURES ? "OGC API"
                                : cs.getProtocol() == FeatureSource.Protocol.PMTILES ? "PMTiles" : "ArcGIS") + "</font></html>";
                super.getListCellRendererComponent(list, text, index, sel, focus);
                setToolTipText(Utils.escapeReservedCharactersHTML(cs.getUrl()));
                return this;
            }
        });
        JButton add = new JButton(tr("Add..."));
        JButton edit = new JButton(tr("Edit..."));
        JButton remove = new JButton(tr("Remove"));
        edit.setEnabled(false);
        remove.setEnabled(false);
        sourceList.addListSelectionListener(e -> {
            edit.setEnabled(sourceList.getSelectedIndex() >= 0);
            remove.setEnabled(sourceList.getSelectedIndex() >= 0);
        });
        add.addActionListener(e -> {
            CustomSource cs = CustomSourceEditor.edit(sourceList, null, CustomSourceEditor.namesExcept(currentSources(), null));
            if (cs != null) {
                sources.addElement(cs);
                sourceList.setSelectedValue(cs, true);
            }
        });
        edit.addActionListener(e -> {
            int i = sourceList.getSelectedIndex();
            if (i >= 0) {
                CustomSource old = sources.get(i);
                CustomSource cs = CustomSourceEditor.edit(sourceList, old, CustomSourceEditor.namesExcept(currentSources(), old));
                if (cs != null) {
                    sources.set(i, cs);
                }
            }
        });
        remove.addActionListener(e -> {
            int i = sourceList.getSelectedIndex();
            if (i >= 0) {
                sources.remove(i);
            }
        });

        JPanel buttons = new JPanel(new GridBagLayout());
        GridBagConstraints b = new GridBagConstraints();
        b.gridx = 0;
        b.fill = GridBagConstraints.HORIZONTAL;
        b.insets = new Insets(0, 4, 4, 0);
        buttons.add(add, b);
        buttons.add(edit, b);
        buttons.add(remove, b);
        b.weighty = 1;
        buttons.add(new JPanel(), b);

        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.fill = GridBagConstraints.BOTH;
        gc.weightx = 1;
        gc.weighty = 1;
        p.add(new JScrollPane(sourceList), gc);
        gc.weightx = 0;
        p.add(buttons, gc);
        return p;
    }

    private List<CustomSource> currentSources() {
        List<CustomSource> out = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            out.add(sources.get(i));
        }
        return out;
    }

    @Override
    public boolean ok() {
        Config.getPref().putDouble(PREF_MATCH_DISTANCE, ((Number) matchDistance.getValue()).doubleValue());
        Config.getPref().putDouble(PREF_AMBIGUITY_RATIO, ((Number) ambiguityRatio.getValue()).doubleValue());
        Config.getPref().putDouble(PREF_SPLIT_TOLERANCE, ((Number) splitTolerance.getValue()).doubleValue() / 100);
        List<String> ob = Arrays.stream(outbuildings.getText().split("[,;\\s]+")).map(String::trim)
                .filter(x -> !x.isEmpty()).collect(Collectors.toList());
        Config.getPref().putList(PREF_OUTBUILDINGS, ob);
        Config.getPref().putBoolean(PREF_DELETE_SOURCE, deleteSource.isSelected());
        CustomSource.save(currentSources());
        return false;
    }
}
