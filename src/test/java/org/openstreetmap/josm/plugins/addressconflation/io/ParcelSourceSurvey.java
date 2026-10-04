// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.io;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.progress.NullProgressMonitor;
import org.openstreetmap.josm.plugins.addressconflation.JosmTestSetup;
import org.openstreetmap.josm.tools.HttpClient;

/**
 * Not a regular test: a survey of every OpenAddresses parcel source in a country, run
 * through the plugin's own download path. Skipped unless SURVEY_OUT names an output
 * file (one JSON object per source). SURVEY_PREFIX picks the sources (default "us/").
 *
 * <pre>SURVEY_OUT=/tmp/survey.jsonl ./gradlew test --tests '*ParcelSourceSurvey'</pre>
 *
 * For each source: read its definition, take a parcel from its latest OpenAddresses job
 * sample, and download parcels for a ~300 m box around it.
 */
class ParcelSourceSurvey {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static final String API = "https://batch.openaddresses.io/api";
    private static final String RAW = "https://raw.githubusercontent.com/openaddresses/openaddresses/master/sources/";
    private static final double HALF_BOX = 0.0015;

    @Test
    void survey() throws Exception {
        String out = System.getenv("SURVEY_OUT");
        assumeTrue(out != null && !out.isEmpty(), "set SURVEY_OUT to run the parcel source survey");
        String prefix = System.getenv().getOrDefault("SURVEY_PREFIX", "us/");
        JsonArray datasets = readArray(fetch(API + "/data?layer=parcels"));
        List<JsonObject> todo = new ArrayList<>();
        for (JsonValue v : datasets) {
            JsonObject d = v.asJsonObject();
            if (d.getString("source", "").startsWith(prefix)) {
                todo.add(d);
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<JsonObject>> results = new ArrayList<>();
        for (JsonObject d : todo) {
            results.add(pool.submit(() -> probe(d)));
        }
        pool.shutdown();
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Paths.get(out), StandardCharsets.UTF_8))) {
            for (Future<JsonObject> f : results) {
                w.println(f.get(10, TimeUnit.MINUTES).toString());
                w.flush();
            }
        }
    }

    private static JsonObject probe(JsonObject d) {
        String source = d.getString("source");
        JsonObjectBuilder r = Json.createObjectBuilder().add("source", source).add("level", d.getString("name", ""))
                .add("updated", d.getString("updated", ""));
        long t0 = System.currentTimeMillis();
        try {
            String text;
            try {
                text = new String(fetch(RAW + source + ".json"), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return r.add("status", "definition-unavailable").add("message", String.valueOf(e.getMessage())).build();
            }
            JsonObject def;
            try (JsonReader jr = Json.createReader(new StringReader(text))) {
                def = jr.readObject();
            }
            JsonArray layers = def.containsKey("layers") && def.getJsonObject("layers").containsKey("parcels")
                    ? def.getJsonObject("layers").getJsonArray("parcels") : JsonValue.EMPTY_JSON_ARRAY;
            JsonObject first = layers.isEmpty() ? JsonValue.EMPTY_JSON_OBJECT : layers.getJsonObject(0);
            r.add("protocol", first.getString("protocol", "?"));
            r.add("data", first.getString("data", ""));
            if (first.containsKey("conform")) {
                r.add("conform", first.getJsonObject("conform"));
            }
            EsriFeatureSource esri = null;
            for (EsriFeatureSource s : OpenAddressesSourceReader.parse(text, false)) {
                if (s.getKind() == EsriFeatureSource.Kind.PARCELS) {
                    esri = s;
                    break;
                }
            }
            if (esri == null) {
                return r.add("status", "ESRI".equals(first.getString("protocol", "")) ? "esri-not-parsed" : "not-esri").build();
            }
            double[] at = samplePoint(d.getJsonNumber("job").longValue());
            if (at == null) {
                return r.add("status", "no-sample").build();
            }
            r.add("lon", at[0]).add("lat", at[1]);
            Bounds box = new Bounds(at[1] - HALF_BOX, at[0] - HALF_BOX, at[1] + HALF_BOX, at[0] + HALF_BOX);
            EsriFeatureSource matched = EsriFeatureClient.matchServiceFields(esri);
            r.add("fieldsBefore", esri.outFields()).add("fieldsAfter", matched.outFields());
            DataSet ds;
            try {
                ds = EsriFeatureClient.download(esri, box, NullProgressMonitor.INSTANCE);
            } catch (IOException | RuntimeException e) {
                return r.add("status", "download-failed").add("error", e.getClass().getSimpleName())
                        .add("message", String.valueOf(e.getMessage())).add("ms", System.currentTimeMillis() - t0).build();
            }
            long withPid = ds.allPrimitives().stream().filter(p -> p.hasKey("oa:pid")).count();
            int n = ds.getWays().size() + ds.getRelations().size();
            return r.add("status", n == 0 ? "empty" : "ok").add("features", n).add("withPid", withPid)
                    .add("wantsPid", esri.getConform().containsKey("pid")).add("ms", System.currentTimeMillis() - t0).build();
        } catch (Exception e) {
            return r.add("status", "probe-error").add("error", e.getClass().getSimpleName()).add("message", String.valueOf(e.getMessage())).build();
        }
    }

    /** First coordinate of the first sample feature of the job, as lon, lat. */
    private static double[] samplePoint(long job) throws IOException {
        JsonArray sample = readArray(fetch(API + "/job/" + job + "/output/sample"));
        for (JsonValue v : sample) {
            JsonObject g = v.asJsonObject().getJsonObject("geometry");
            if (g == null) {
                continue;
            }
            JsonValue c = g.get("coordinates");
            while (c != null && c.getValueType() == JsonValue.ValueType.ARRAY && !c.asJsonArray().isEmpty()
                    && c.asJsonArray().get(0).getValueType() == JsonValue.ValueType.ARRAY) {
                c = c.asJsonArray().get(0);
            }
            if (c != null && c.getValueType() == JsonValue.ValueType.ARRAY && c.asJsonArray().size() >= 2) {
                return new double[] {c.asJsonArray().getJsonNumber(0).doubleValue(), c.asJsonArray().getJsonNumber(1).doubleValue()};
            }
        }
        return null;
    }

    private static JsonArray readArray(byte[] b) {
        try (JsonReader r = Json.createReader(new StringReader(new String(b, StandardCharsets.UTF_8)))) {
            return r.readArray();
        }
    }

    private static byte[] fetch(String url) throws IOException {
        HttpClient.Response resp = HttpClient.create(URI.create(url).toURL()).setConnectTimeout(15000).setReadTimeout(60000).connect();
        try {
            if (resp.getResponseCode() != 200) {
                throw new IOException("HTTP " + resp.getResponseCode());
            }
            return resp.getContent().readAllBytes();
        } finally {
            resp.disconnect();
        }
    }
}
