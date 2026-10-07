// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.gui;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Color;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.plugins.addressconflation.io.CustomSource;
import org.openstreetmap.josm.plugins.addressconflation.io.FeatureSource.Kind;
import org.openstreetmap.josm.plugins.addressconflation.io.FeatureSource.Protocol;
import org.openstreetmap.josm.plugins.addressconflation.io.ServiceInspector;
import org.openstreetmap.josm.plugins.addressconflation.license.LicenseBadge;
import org.openstreetmap.josm.plugins.addressconflation.license.Licensing;

/**
 * Add or edit one of the mapper's own sources: paste the layer URL, Check it to read its
 * fields and coverage, say what it holds and which fields carry what.
 */
public final class CustomSourceEditor extends ExtendedDialog {
    private final JTextField url = new JTextField(42);
    private final JButton check = new JButton(tr("Check"));
    /** Check results and errors; a text area so even a long unbroken address wraps. */
    private final JTextArea status = new JTextArea(1, 42);
    private final JComboBox<Protocol> protocol = new JComboBox<>(Protocol.values());
    private final JTextField name = new JTextField(30);
    private final JComboBox<Kind> kind = new JComboBox<>(Kind.values());
    private final JPanel fieldsPanel = new JPanel(new GridBagLayout());
    private final Map<String, JComboBox<String>> fieldBoxes = new LinkedHashMap<>();
    /** What the mapper chose per property, kept while they switch kinds. */
    private final Map<String, String> chosen = new HashMap<>();
    private final JLabel coverage = new JLabel(" ");
    private final Collection<String> takenNames;

    private List<String> serviceFields = Collections.emptyList();
    private ServiceInspector.Geometry geometry = ServiceInspector.Geometry.UNKNOWN;
    private Bounds extent;
    /** The URL and protocol the last successful Check was for. */
    private String checkedUrl;
    private Protocol checkedProtocol;
    private CustomSource result;
    /** True once the mapper chose Used as themselves; Check then leaves it alone (unless the layer is points). */
    private boolean kindPickedByMapper;
    /** Set while Check changes Used as, so that does not count as the mapper's choice. */
    private boolean settingKind;

    private CustomSourceEditor(Component parent, CustomSource existing, Collection<String> takenNames) {
        super(parent, existing == null ? tr("Add a source") : tr("Edit source"), new String[] {tr("Save"), tr("Cancel")}, true);
        this.takenNames = takenNames;
        setButtonIcons("ok", "cancel");
        setContent(build(), false);
        if (existing != null) {
            url.setText(existing.getUrl());
            protocol.setSelectedItem(existing.getProtocol());
            name.setText(existing.getName());
            kind.setSelectedItem(existing.getKind());
            kindPickedByMapper = true;
            for (Map.Entry<String, List<String>> e : existing.getFields().entrySet()) {
                chosen.put(e.getKey(), CustomSource.formatFields(e.getValue()));
            }
            extent = existing.getExtent();
            // Saved after a Check; unchanged, it needs no new one.
            checkedUrl = existing.getUrl();
            checkedProtocol = existing.getProtocol();
        }
        rebuildFields();
        updateCoverage();
    }

    /**
     * Show the editor.
     *
     * @param existing   the source to edit, or null to add one
     * @param takenNames names of the other sources, which this one may not reuse
     * @return the saved source, or null when cancelled
     */
    public static CustomSource edit(Component parent, CustomSource existing, Collection<String> takenNames) {
        CustomSourceEditor dlg = new CustomSourceEditor(parent, existing, takenNames);
        dlg.showDialog();
        return dlg.getValue() == 1 ? dlg.result : null;
    }

    // ---- layout --------------------------------------------------------------------------

    private JPanel build() {
        protocol.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                return super.getListCellRendererComponent(list, value == Protocol.OGC_FEATURES ? tr("OGC API - Features collection")
                        : value == Protocol.PMTILES ? tr("PMTiles vector tiles (address points only)")
                        : tr("ArcGIS REST layer (FeatureServer or MapServer)"), index, sel, focus);
            }
        });
        kind.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                return super.getListCellRendererComponent(list, kindLabel((Kind) value), index, sel, focus);
            }
        });
        url.setToolTipText(tr("<html>The layer''s URL, for example<br>https://gis.example.gov/arcgis/rest/services/Parcels/FeatureServer/0<br>"
                + "https://maps.example.gov/ogc/collections/parcels<br>"
                + "https://data.example.org/nad-r24.pmtiles</html>"));
        check.setToolTipText(tr("Ask the service for the layer''s fields, geometry and coverage"));
        url.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                urlChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                urlChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                urlChanged();
            }
        });
        check.addActionListener(e -> runCheck());
        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        status.setOpaque(false);
        status.setBorder(null);
        status.setFont(UIManager.getFont("Label.font"));
        kind.addActionListener(e -> {
            if (!settingKind) {
                kindPickedByMapper = true;
            }
            rememberFields();
            rebuildFields();
        });

        JPanel urlRow = new JPanel(new GridBagLayout());
        GridBagConstraints u = new GridBagConstraints();
        u.fill = GridBagConstraints.HORIZONTAL;
        u.weightx = 1;
        urlRow.add(url, u);
        u.weightx = 0;
        u.insets = new Insets(0, 4, 0, 0);
        urlRow.add(check, u);

        JPanel licence = new JPanel(new GridBagLayout());
        GridBagConstraints l = new GridBagConstraints();
        l.anchor = GridBagConstraints.WEST;
        licence.add(new LicenseBadge(Licensing.userProvided()), l);
        l.insets = new Insets(0, 8, 0, 0);
        licence.add(new JLabel(tr("<html>By saving, you confirm the data''s terms allow use in OpenStreetMap.</html>")), l);

        JPanel p = new JPanel(new GridBagLayout());
        int row = 0;
        addRow(p, row++, tr("URL:"), urlRow);
        addRow(p, row++, "", status);
        addRow(p, row++, tr("Type:"), protocol);
        addRow(p, row++, tr("Name:"), name);
        addRow(p, row++, tr("Used as:"), kind);
        addRow(p, row++, tr("Fields:"), fieldsPanel);
        addRow(p, row++, tr("Coverage:"), coverage);
        addRow(p, row, tr("Licence:"), licence);
        return p;
    }

    private static void addRow(JPanel p, int row, String label, Component field) {
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 3, 3, 3);
        gc.anchor = GridBagConstraints.NORTHWEST;
        gc.gridy = row;
        gc.gridx = 0;
        p.add(new JLabel(label), gc);
        gc.gridx = 1;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        p.add(field, gc);
    }

    /** How a kind of source is named in lists. */
    public static String kindLabel(Kind k) {
        switch (k) {
        case ADDRESSES:
            return tr("Addresses");
        case PARCELS:
            return tr("Parcels");
        default:
            return tr("Building outlines (hints)");
        }
    }

    private void rebuildFields() {
        fieldsPanel.removeAll();
        fieldBoxes.clear();
        Kind k = (Kind) kind.getSelectedItem();
        List<String> keys = CustomSource.keysFor(k);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(1, 0, 1, 4);
        gc.anchor = GridBagConstraints.WEST;
        if (keys.isEmpty()) {
            fieldsPanel.add(new JLabel(tr("None needed: every outline becomes building=yes, used for position only.")), gc);
        }
        int row = 0;
        for (String key : keys) {
            JComboBox<String> box = new JComboBox<>();
            box.setEditable(true);
            box.addItem("");
            for (String f : serviceFields) {
                box.addItem(f);
            }
            String value = chosen.get(key);
            if (value == null || value.isEmpty()) {
                String guess = CustomSource.guessField(key, serviceFields);
                value = guess == null ? "" : guess;
            }
            box.setSelectedItem(value);
            box.setToolTipText(tr("Pick the field, or type several joined with + (PREFIX + NAME + SUFFIX)"));
            fieldBoxes.put(key, box);
            gc.gridy = row++;
            gc.gridx = 0;
            gc.weightx = 0;
            gc.fill = GridBagConstraints.NONE;
            fieldsPanel.add(new JLabel(CustomSource.label(key) + (CustomSource.isRequired(k, key) ? " *" : "")), gc);
            gc.gridx = 1;
            gc.weightx = 1;
            gc.fill = GridBagConstraints.HORIZONTAL;
            fieldsPanel.add(box, gc);
        }
        fieldsPanel.revalidate();
        fieldsPanel.repaint();
        pack();
    }

    private void rememberFields() {
        for (Map.Entry<String, JComboBox<String>> e : fieldBoxes.entrySet()) {
            Object v = e.getValue().getEditor().getItem();
            chosen.put(e.getKey(), v == null ? "" : v.toString().trim());
        }
    }

    private void updateCoverage() {
        coverage.setText(extent == null ? tr("Unknown; offered for every area")
                : String.format(Locale.ROOT, "%.3f, %.3f to %.3f, %.3f", extent.getMinLat(), extent.getMinLon(), extent.getMaxLat(),
                        extent.getMaxLon()));
    }

    private void urlChanged() {
        Protocol detected = CustomSource.detect(url.getText());
        if (detected != null) {
            protocol.setSelectedItem(detected);
        }
        setStatus(" ", false);
    }

    /** Longest message shown; servers can send whole pages. */
    private static final int MAX_STATUS = 400;

    private void setStatus(String text, boolean error) {
        String t = text.trim().isEmpty() ? " " : text.trim();
        status.setText(t.length() > MAX_STATUS ? t.substring(0, MAX_STATUS) + "..." : t);
        status.setCaretPosition(0);
        status.setForeground(error ? new Color(0xC62828) : UIManager.getColor("Label.foreground"));
        status.revalidate();
        // Make room for a message that wrapped onto more lines.
        if (isShowing() && getPreferredSize().height > getHeight()) {
            pack();
        }
    }

    // ---- check ---------------------------------------------------------------------------

    private void runCheck() {
        String u = CustomSource.cleanUrl(url.getText());
        if (u.isEmpty()) {
            setStatus(tr("Paste the layer''s URL first."), true);
            return;
        }
        if (!CustomSource.isWebAddress(u)) {
            setStatus(tr("That is not a web address. Paste the layer''s URL, starting with https://"), true);
            return;
        }
        if (!u.equals(url.getText().trim())) {
            // Pasted from a log or a query: keep just the layer.
            url.setText(u);
        }
        Protocol p = (Protocol) protocol.getSelectedItem();
        check.setEnabled(false);
        setStatus(tr("Checking..."), false);
        new SwingWorker<ServiceInspector.Info, Void>() {
            @Override
            protected ServiceInspector.Info doInBackground() throws Exception {
                return ServiceInspector.inspect(u, p);
            }

            @Override
            protected void done() {
                check.setEnabled(true);
                try {
                    checked(u, p, get());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    if (ex.getCause() instanceof ServiceInspector.NotALayerException
                            && !((ServiceInspector.NotALayerException) ex.getCause()).getLayers().isEmpty()) {
                        pickLayer(u, (ServiceInspector.NotALayerException) ex.getCause());
                    } else {
                        setStatus(tr("Check failed: {0}", ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()), true);
                    }
                }
            }
        }.execute();
    }

    /**
     * The URL named a whole service or a list of collections: take its only layer, or let the
     * mapper pick one, then check that.
     */
    private void pickLayer(String checkedFrom, ServiceInspector.NotALayerException e) {
        Map<String, String> layers = e.getLayers();
        String chosenUrl = null;
        if (layers.size() == 1) {
            chosenUrl = layers.keySet().iterator().next();
        } else {
            String[] labels = layers.values().toArray(new String[0]);
            Object pick = JOptionPane.showInputDialog(this, tr("The URL is {0}. Which one do you want?",
                    e.getMessage().replaceFirst("^this is ", "").replaceFirst("^this lists ", "a list of ")),
                    getTitle(), JOptionPane.QUESTION_MESSAGE, null, labels, labels[0]);
            for (Map.Entry<String, String> l : layers.entrySet()) {
                if (l.getValue().equals(pick)) {
                    chosenUrl = l.getKey();
                }
            }
        }
        if (chosenUrl == null || chosenUrl.equals(checkedFrom)) {
            setStatus(tr("Pick one layer, or paste the URL of one."), true);
            return;
        }
        url.setText(chosenUrl);
        runCheck();
    }

    private void checked(String u, Protocol p, ServiceInspector.Info info) {
        rememberFields();
        checkedUrl = u;
        checkedProtocol = p;
        serviceFields = info.getFields();
        geometry = info.getGeometry();
        extent = info.getExtent();
        if (name.getText().trim().isEmpty() && info.getTitle() != null) {
            name.setText(info.getTitle());
        }
        // Points can only be addresses; otherwise suggest from the layer's name and geometry,
        // unless the mapper already chose.
        Kind suggested = p == Protocol.PMTILES ? Kind.ADDRESSES : CustomSource.suggestKind(info.getTitle(), u, geometry);
        if (suggested != null && (geometry == ServiceInspector.Geometry.POINTS || p == Protocol.PMTILES || !kindPickedByMapper)) {
            settingKind = true;
            try {
                kind.setSelectedItem(suggested);
            } finally {
                settingKind = false;
            }
        }
        rebuildFields();
        updateCoverage();
        String what = geometry == ServiceInspector.Geometry.POINTS ? tr("points")
                : geometry == ServiceInspector.Geometry.POLYGONS ? tr("polygons")
                : geometry == ServiceInspector.Geometry.OTHER ? tr("lines or other shapes") : tr("unknown geometry");
        setStatus(tr("Found {0}: {1}, {2} fields. Used as {3}.", info.getTitle() == null ? tr("the layer") : info.getTitle(), what,
                serviceFields.size(), kindLabel((Kind) kind.getSelectedItem()).toLowerCase(Locale.ROOT)), false);
    }

    // ---- save ----------------------------------------------------------------------------

    @Override
    protected void buttonAction(int buttonIndex, ActionEvent evt) {
        if (buttonIndex == 0) {
            CustomSource s = validated();
            if (s == null) {
                return;
            }
            result = s;
        }
        super.buttonAction(buttonIndex, evt);
    }

    /** The source as entered, or null after telling the mapper what is missing. */
    private CustomSource validated() {
        rememberFields();
        String u = url.getText().trim();
        Protocol p = (Protocol) protocol.getSelectedItem();
        Kind k = (Kind) kind.getSelectedItem();
        String n = name.getText().trim();
        if (u.isEmpty()) {
            return refuse(tr("Paste the layer''s URL."));
        }
        if (!u.equals(checkedUrl) || p != checkedProtocol) {
            return refuse(tr("Click Check first, so the plugin can read the layer''s fields and coverage."));
        }
        if (n.isEmpty()) {
            return refuse(tr("Give the source a name; it becomes the layer name."));
        }
        for (String taken : takenNames) {
            if (taken.equalsIgnoreCase(n)) {
                return refuse(tr("Another source is already called {0}.", n));
            }
        }
        if (p == Protocol.PMTILES && k != Kind.ADDRESSES) {
            return refuse(tr("Only address points can be read from PMTiles."));
        }
        if (geometry == ServiceInspector.Geometry.POINTS && k != Kind.ADDRESSES) {
            return refuse(tr("This layer holds points, so it can only be used for addresses."));
        }
        if (geometry == ServiceInspector.Geometry.OTHER) {
            return refuse(tr("This layer holds neither points nor polygons."));
        }
        Map<String, List<String>> fields = new LinkedHashMap<>();
        for (String key : CustomSource.keysFor(k)) {
            List<String> f = CustomSource.parseFields(chosen.get(key));
            if (f.isEmpty() && CustomSource.isRequired(k, key)) {
                return refuse(tr("Pick the field that holds the {0}.", CustomSource.label(key).toLowerCase(Locale.ROOT)));
            }
            for (String one : f) {
                if (!serviceFields.isEmpty() && serviceFields.stream().noneMatch(sf -> sf.equalsIgnoreCase(one))) {
                    return refuse(tr("The layer has no field called {0}.", one));
                }
            }
            if (!f.isEmpty()) {
                fields.put(key, f);
            }
        }
        return new CustomSource(n, p, u, k, fields, extent);
    }

    private CustomSource refuse(String why) {
        JOptionPane.showMessageDialog(this, why, getTitle(), JOptionPane.WARNING_MESSAGE);
        return null;
    }

    /** Names of the sources other than {@code except}, for {@link #edit}. */
    public static List<String> namesExcept(List<CustomSource> sources, CustomSource except) {
        List<String> out = new ArrayList<>();
        for (CustomSource s : sources) {
            if (s != except) {
                out.add(s.getName());
            }
        }
        return out;
    }
}
