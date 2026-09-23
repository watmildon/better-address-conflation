// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.gui;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.function.Predicate;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;

import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.addressconflation.io.OpenAddressesLayer;
import org.openstreetmap.josm.plugins.addressconflation.io.OpenAddressesReader;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Popup shown by Analyze: pick the address, parcel and hint layers and the
 * analysis options. Defaults favour layers the plugin downloaded itself.
 */
public final class AnalysisSetupDialog {
    static final String VORONOI = "voronoi";
    static final String NO_HINTS = "nohints";
    private static final String PREF_ROAD_CLIP = "addressconflation.roadClip";

    /** What the user picked. */
    public static final class Choice {
        final OsmDataLayer addressLayer;
        /** Parcel layer, or null for Voronoi cells. */
        final OsmDataLayer parcelLayer;
        /** Hint layer, or null for none. */
        final OsmDataLayer hintLayer;
        final boolean roadClip;

        Choice(OsmDataLayer addressLayer, OsmDataLayer parcelLayer, OsmDataLayer hintLayer, boolean roadClip) {
            this.addressLayer = addressLayer;
            this.parcelLayer = parcelLayer;
            this.hintLayer = hintLayer;
            this.roadClip = roadClip;
        }

        boolean uses(Layer l) {
            return l == addressLayer || l == parcelLayer || l == hintLayer;
        }
    }

    private AnalysisSetupDialog() {
    }

    /** Show the popup for the given edit layer; null when cancelled. */
    public static Choice show(OsmDataLayer edit) {
        List<OsmDataLayer> layers = MainApplication.getLayerManager().getLayersOfType(OsmDataLayer.class);
        JComboBox<OsmDataLayer> addressBox = new JComboBox<>();
        JComboBox<Object> cellBox = new JComboBox<>();
        JComboBox<Object> hintBox = new JComboBox<>();
        cellBox.addItem(VORONOI);
        hintBox.addItem(NO_HINTS);
        for (OsmDataLayer l : layers) {
            addressBox.addItem(l);
            cellBox.addItem(l);
            if (l != edit) {
                hintBox.addItem(l);
            }
        }
        addressBox.setSelectedItem(defaultAddressLayer(layers, edit));
        OsmDataLayer parcels = defaultParcelLayer(layers);
        cellBox.setSelectedItem(parcels != null ? parcels : VORONOI);
        OsmDataLayer hints = defaultHintLayer(layers, edit);
        hintBox.setSelectedItem(hints != null ? hints : NO_HINTS);

        DefaultListCellRenderer renderer = new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                Object v = value instanceof Layer ? ((Layer) value).getName()
                        : VORONOI.equals(value) ? tr("Voronoi cells (no parcel layer)")
                        : NO_HINTS.equals(value) ? tr("None") : value;
                return super.getListCellRendererComponent(list, v, index, isSelected, cellHasFocus);
            }
        };
        addressBox.setRenderer(renderer);
        cellBox.setRenderer(renderer);
        hintBox.setRenderer(renderer);
        addressBox.setToolTipText(tr("Layer whose address nodes are matched to buildings. Pick the active layer itself to tidy up its own loose address nodes."));
        cellBox.setToolTipText(tr("Parcel polygons that decide which building each address belongs to. Without one, Voronoi cells around the address points stand in."));
        hintBox.setToolTipText(tr("Building footprints used for position only (Microsoft, MapWithAI or county footprints). Never edited."));

        JCheckBox roadClip = new JCheckBox(tr("Clip Voronoi cells by roads"), Config.getPref().getBoolean(PREF_ROAD_CLIP, true));
        roadClip.setToolTipText(tr("With no parcel layer, cut Voronoi cells along streets so a cell never reaches the house across the road"));
        roadClip.setEnabled(VORONOI.equals(cellBox.getSelectedItem()));
        cellBox.addActionListener(e -> roadClip.setEnabled(VORONOI.equals(cellBox.getSelectedItem())));

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 3, 3, 3);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;
        JLabel buildings = new JLabel(edit.getName());
        buildings.setToolTipText(tr("The active layer: its buildings get the addresses. Make another layer active to change this."));
        addRow(panel, gc, 0, tr("Buildings:"), buildings);
        addRow(panel, gc, 1, tr("Addresses:"), addressBox);
        addRow(panel, gc, 2, tr("Parcels:"), cellBox);
        addRow(panel, gc, 3, tr("Hints:"), hintBox);
        gc.gridx = 1;
        gc.gridy = 4;
        panel.add(roadClip, gc);

        ExtendedDialog dlg = new ExtendedDialog(MainApplication.getMainFrame(), tr("Analyze addresses"), tr("Analyze"), tr("Cancel"));
        dlg.setContent(panel, false);
        dlg.setButtonIcons("ok", "cancel");
        if (dlg.showDialog().getValue() != 1 || addressBox.getSelectedItem() == null) {
            return null;
        }
        Config.getPref().putBoolean(PREF_ROAD_CLIP, roadClip.isSelected());
        Object cell = cellBox.getSelectedItem();
        Object hint = hintBox.getSelectedItem();
        return new Choice((OsmDataLayer) addressBox.getSelectedItem(),
                cell instanceof OsmDataLayer ? (OsmDataLayer) cell : null,
                hint instanceof OsmDataLayer ? (OsmDataLayer) hint : null,
                roadClip.isSelected());
    }

    private static void addRow(JPanel panel, GridBagConstraints gc, int row, String label, Component field) {
        gc.gridy = row;
        gc.gridx = 0;
        gc.weightx = 0;
        panel.add(new JLabel(label), gc);
        gc.gridx = 1;
        gc.weightx = 1;
        panel.add(field, gc);
    }

    // ---- defaults -------------------------------------------------------------------

    /** A downloaded address layer, else one whose name mentions addresses, else any non-edit layer, else the edit layer. */
    static OsmDataLayer defaultAddressLayer(List<OsmDataLayer> layers, OsmDataLayer edit) {
        OsmDataLayer l = first(layers, x -> x != edit && isDownloaded(x, OpenAddressesReader.Layer.ADDRESSES));
        if (l == null) {
            l = first(layers, x -> x != edit && x.getName().toLowerCase().contains("addr"));
        }
        if (l == null) {
            l = first(layers, x -> x != edit);
        }
        return l != null ? l : edit;
    }

    /** A downloaded parcel layer, else one named like parcels, else null (Voronoi). */
    static OsmDataLayer defaultParcelLayer(List<OsmDataLayer> layers) {
        OsmDataLayer l = first(layers, x -> isDownloaded(x, OpenAddressesReader.Layer.PARCELS));
        return l != null ? l : first(layers, x -> x.getName().toLowerCase().contains("parcel"));
    }

    /** A downloaded footprint layer, else one named like footprints or MapWithAI, else null. */
    static OsmDataLayer defaultHintLayer(List<OsmDataLayer> layers, OsmDataLayer edit) {
        OsmDataLayer l = first(layers, x -> x != edit && isDownloaded(x, OpenAddressesReader.Layer.BUILDINGS));
        return l != null ? l : first(layers, x -> {
            String n = x.getName().toLowerCase();
            return x != edit && (n.contains("mapwithai") || n.contains("building") || n.contains("footprint"));
        });
    }

    private static boolean isDownloaded(OsmDataLayer l, OpenAddressesReader.Layer kind) {
        return l instanceof OpenAddressesLayer && ((OpenAddressesLayer) l).getKind() == kind;
    }

    private static OsmDataLayer first(List<OsmDataLayer> layers, Predicate<OsmDataLayer> p) {
        return layers.stream().filter(p).findFirst().orElse(null);
    }
}
