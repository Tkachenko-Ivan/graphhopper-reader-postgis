package com.graphhopper.reader.postgis;

import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.reader.DataReader;
import com.graphhopper.reader.osm.GraphHopperOSM;
import com.graphhopper.storage.GraphHopperStorage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class GraphHopperPostgis extends GraphHopperOSM {

    private final HashSet<OSMPostgisReader.EdgeAddedListener> edgeAddedListeners = new HashSet<>();
    private final Map<String, String> postgisParams = new HashMap<>();

    @Override
    public GraphHopper init(GraphHopperConfig ghConfig) {

        postgisParams.put("dbtype", "postgis");
        postgisParams.put("host", ghConfig.getString("db.host", ""));
        postgisParams.put("port", ghConfig.getString("db.port", "5432"));
        postgisParams.put("schema", ghConfig.getString("db.schema", ""));
        postgisParams.put("database", ghConfig.getString("db.database", ""));
        postgisParams.put("user", ghConfig.getString("db.user", ""));
        postgisParams.put("passwd", ghConfig.getString("db.passwd", ""));
        postgisParams.put("tags_to_copy", ghConfig.getString("db.tags_to_copy", ""));

        return super.init(ghConfig);
    }

    @Override
    protected DataReader createReader(GraphHopperStorage ghStorage) {
        OSMPostgisReader reader = new OSMPostgisReader(ghStorage, postgisParams);
        for (OSMPostgisReader.EdgeAddedListener l : edgeAddedListeners) {
            reader.addListener(l);
        }
        return initDataReader(reader);
    }

    public void addListener(OSMPostgisReader.EdgeAddedListener l) {
        edgeAddedListeners.add(l);
    }

}
