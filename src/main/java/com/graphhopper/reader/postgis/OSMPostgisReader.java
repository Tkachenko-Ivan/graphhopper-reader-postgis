package com.graphhopper.reader.postgis;

import com.graphhopper.coll.GHObjectIntHashMap;
import com.graphhopper.reader.DataReader;
import com.graphhopper.reader.OSMTurnRelation;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.reader.dem.ElevationProvider;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.parsers.TurnCostParser;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.IntsRef;
import com.graphhopper.util.DistanceCalc;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.Helper;
import com.graphhopper.util.PointList;
import com.graphhopper.util.shapes.GHPoint;
import com.vividsolutions.jts.geom.Coordinate;
import org.geotools.data.DataStore;
import org.geotools.feature.FeatureIterator;
import org.opengis.feature.simple.SimpleFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.util.*;
import static com.graphhopper.util.DistanceCalcEarth.DIST_EARTH;
import static com.graphhopper.util.Helper.nf;
import static com.graphhopper.util.Helper.toLowerCase;

/**
 * Reads OSM data from Postgis and uses it in GraphHopper
 *
 * @author Vikas Veshishth
 * @author Philip Welch
 * @author Mario Basa
 * @author Robin Boldt
 */
public class OSMPostgisReader extends PostgisReader implements TurnCostParser.ExternalInternalMap {

    private static final Logger LOGGER = LoggerFactory.getLogger(OSMPostgisReader.class);

    private static final int COORD_STATE_UNKNOWN = 0;
    private static final int COORD_STATE_PILLAR = -2;
    private static final int FIRST_NODE_ID = 1;

    private GHObjectIntHashMap<Coordinate> coordState = new GHObjectIntHashMap<>(10_000_000, 0.7f);
    private final HashSet<EdgeAddedListener> edgeAddedListeners = new HashSet<>();

    private int nextNodeId = FIRST_NODE_ID;

    private final String[] tagsToCopy;
    private File roadsFile;
    private final DistanceCalc distCalc = DIST_EARTH;
    protected long zeroCounter = 0;

    private final IntsRef tempRelFlags;

    private final HashMap<Long, WayNodes> wayNodesMap = new HashMap<>();
    private final HashMap<Integer, Long> edgeOsmIdMap = new HashMap<>();

    public OSMPostgisReader(GraphHopperStorage ghStorage, Map<String, String> postgisParams) {
        super(ghStorage, postgisParams);

        String tmpTagsToCopy = postgisParams.get("tags_to_copy");
        if (tmpTagsToCopy == null || tmpTagsToCopy.isEmpty()) {
            this.tagsToCopy = new String[]{};
        } else {
            this.tagsToCopy = tmpTagsToCopy.split(",");
        }
        tempRelFlags = encodingManager.createRelationFlags();
        if (tempRelFlags.length != 2) {
            throw new IllegalArgumentException("Cannot use relation flags with != 2 integers");
        }
        tempRelFlags.ints[0] = (int) 0L;
        tempRelFlags.ints[1] = (int) 0L;
    }

    @Override
    void processJunctions() {
        DataStore dataStore = null;
        FeatureIterator<SimpleFeature> roads = null;
        int tmpJunctionCounter = 0;

        try {
            dataStore = openPostGisStore();
            roads = getFeatureIterator(dataStore, roadsFile.getName());

            HashSet<Coordinate> tmpSet = new HashSet<>();
            while (roads.hasNext()) {
                SimpleFeature road = roads.next();

                if (!acceptFeature(road)) {
                    continue;
                }

                for (Coordinate[] points : getCoords(road)) {
                    tmpSet.clear();
                    for (int i = 0; i < points.length; i++) {
                        Coordinate c = points[i];
                        c = roundCoordinate(c);

                        // Не добавлять одну и ту же координату дважды для одного ребра - 
                        // происходит с плохой геометрией, 
                        // т.е. дублирующимися координатами, 
                        // или дорогой которая образует круг (например, кольцевая развязка)
                        if (tmpSet.contains(c)) {
                            continue;
                        }

                        tmpSet.add(c);

                        // Пропустить если это уже узел
                        int state = coordState.get(c);
                        if (state >= FIRST_NODE_ID) {
                            continue;
                        }

                        if (i == 0 || i == points.length - 1 || state == COORD_STATE_PILLAR) {
                            // Превратится в УЗЕЛ если это первая или последняя точка, или появлялась в другом ребре
                            int nodeId = nextNodeId++;
                            coordState.put(c, nodeId);
                            saveTowerPosition(nodeId, c);
                        } else if (state == COORD_STATE_UNKNOWN) {
                            // Пометить в качество столба, (который затем может быть превращён в узел)
                            coordState.put(c, COORD_STATE_PILLAR);
                        }

                        if (++tmpJunctionCounter % 100_000 == 0) {
                            LOGGER.info(nf(tmpJunctionCounter) + " (junctions), junctionMap:" + nf(coordState.size())
                                    + " " + Helper.getMemInfo());
                        }
                    }
                }
            }
        } finally {
            if (roads != null) {
                roads.close();
            }
            if (dataStore != null) {
                dataStore.dispose();
            }
        }

        if (nextNodeId == FIRST_NODE_ID) {
            throw new IllegalArgumentException("No data found for roads file " + roadsFile);
        }

        LOGGER.info("Number of junction points : " + (nextNodeId - FIRST_NODE_ID));
    }

    @Override
    void processRoads() {
        DataStore dataStore = null;
        FeatureIterator<SimpleFeature> roads = null;

        int tmpEdgeCounter = 0;

        try {
            dataStore = openPostGisStore();
            roads = getFeatureIterator(dataStore, roadsFile.getName());

            while (roads.hasNext()) {
                SimpleFeature road = roads.next();

                if (!acceptFeature(road)) {
                    continue;
                }

                for (Coordinate[] points : getCoords(road)) {
                    // Парсим все точки в геометрии, разделяя их на отдельные рёбра
                    // всякий раз когда находим узел в списке точек
                    Coordinate startTowerPnt = null;
                    List<Coordinate> pillars = new ArrayList<>();
                    for (Coordinate point : points) {
                        point = roundCoordinate(point);
                        if (startTowerPnt == null) {
                            startTowerPnt = point;
                        } else {
                            int state = coordState.get(point);
                            if (state >= FIRST_NODE_ID) {
                                int fromTowerNodeId = coordState.get(startTowerPnt);
                                int toTowerNodeId = state;

                                // Получить расстояние и приблизительный центр
                                GHPoint estmCentre = new GHPoint(
                                        0.5 * (lat(startTowerPnt) + lat(point)),
                                        0.5 * (lng(startTowerPnt) + lng(point)));
                                PointList pillarNodes = new PointList(pillars.size(), false);

                                for (Coordinate pillar : pillars) {
                                    pillarNodes.add(lat(pillar), lng(pillar));
                                }

                                double distance = getWayLength(startTowerPnt, pillars, point);
                                addEdge(fromTowerNodeId, toTowerNodeId, road, distance, estmCentre, pillarNodes);
                                startTowerPnt = point;
                                pillars.clear();

                                if (++tmpEdgeCounter % 1_000_000 == 0) {
                                    LOGGER.info(nf(tmpEdgeCounter) + " (edges) " + Helper.getMemInfo());
                                }
                            } else {
                                pillars.add(point);
                            }
                        }
                    }
                }

            }
        } finally {
            if (roads != null) {
                roads.close();
            }

            if (dataStore != null) {
                dataStore.dispose();
            }
        }
    }

    @Override
    void processRestrictions() {
        if (wayNodesMap.isEmpty()) {
            LOGGER.info("Список узлов - пустой");
            return;
        }

        DataStore dataStore = null;
        FeatureIterator<SimpleFeature> roads = null;

        try {
            dataStore = openPostGisStore();
            roads = getFeatureIterator(dataStore, roadsFile.getName());

            while (roads.hasNext()) {
                SimpleFeature road = roads.next();
                if (!acceptFeature(road)) {
                    continue;
                }

                String restriction = (String) road.getAttribute("restriction");
                if (restriction == null) {
                    continue;
                }

                OSMTurnRelation.Type type = restriction.equalsIgnoreCase("no")
                        ? OSMTurnRelation.Type.NOT
                        : OSMTurnRelation.Type.getRestrictionType(restriction);
                if (type == OSMTurnRelation.Type.UNSUPPORTED) {
                    LOGGER.info("Unsupported: " + restriction);
                    continue;
                }

                Long restrictionFrom = getOSMId(road);
                Long restrictionTo = (Long) road.getAttribute("restriction_to");
                if (restrictionTo <= 0 || restrictionFrom <= 0) {
                    continue;
                }

                WayNodes fromWay = wayNodesMap.get(restrictionFrom);
                WayNodes toWay = wayNodesMap.get(restrictionTo);

                if (toWay == null || fromWay == null) {
                    continue;
                }

                int nodeId = 0;

                if (fromWay.getToNode() == toWay.getFromNode()) {
                    nodeId = fromWay.getToNode();
                } else if (fromWay.getToNode() == toWay.getToNode()) {
                    nodeId = fromWay.getToNode();
                } else if (fromWay.getFromNode() == toWay.getFromNode()) {
                    nodeId = fromWay.getFromNode();
                } else if (fromWay.getFromNode() == toWay.getToNode()) {
                    nodeId = fromWay.getFromNode();
                } else {
                    continue;
                }

                OSMTurnRelation osmTurnRelation = new OSMTurnRelation(restrictionFrom, nodeId, restrictionTo, type);
                osmTurnRelation.setVehicleTypeRestricted("motorcar");

                LOGGER.info(osmTurnRelation.toString());

                encodingManager.handleTurnRelationTags(osmTurnRelation, this, graph);

            }
        } finally {
            if (roads != null) {
                roads.close();
            }

            if (dataStore != null) {
                dataStore.dispose();
            }
        }
    }

    @Override
    protected void finishReading() {
        this.coordState.clear();
        this.coordState = null;
        LOGGER.info("Finished reading. Zero Counter " + nf(zeroCounter) + " " + Helper.getMemInfo());
    }

    /**
     * Рассчёт расстояния по координатам
     *
     * @param start начальная точка
     * @param pillars промежуточные точки
     * @param end конечная точка
     * @return дистанция
     */
    protected double getWayLength(Coordinate start, List<Coordinate> pillars, Coordinate end) {
        double distance = 0;

        Coordinate previous = start;
        for (Coordinate point : pillars) {
            distance += distCalc.calcDist(lat(previous), lng(previous), lat(point), lng(point));
            previous = point;
        }
        distance += distCalc.calcDist(lat(previous), lng(previous), lat(end), lng(end));

        if (distance < 0.0001) {
            // As investigation shows often two paths should have crossed via one identical point
            // but end up in two very close points.
            zeroCounter++;
            distance = 0.0001;
        }

        if (Double.isNaN(distance)) {
            LOGGER.warn("Bug in OSM or GraphHopper. Illegal tower node distance " + distance + " reset to 1m, osm way " + distance);
            distance = 1;
        }

        return distance;
    }

    @Override
    public DataReader setFile(File file) {
        this.roadsFile = file;
        return this;
    }

    @Override
    public DataReader setElevationProvider(ElevationProvider ep) {
        return this;
    }

    @Override
    public DataReader setWorkerThreads(int workerThreads) {
        return this;
    }

    @Override
    public DataReader setWayPointMaxDistance(double wayPointMaxDistance) {
        return this;
    }

    @Override
    public DataReader setWayPointElevationMaxDistance(double v) {
        return this;
    }

    @Override
    public DataReader setSmoothElevation(boolean smoothElevation) {
        return this;
    }

    @Override
    public DataReader setLongEdgeSamplingDistance(double v) {
        return this;
    }

    @Override
    public Date getDataDate() {
        return null;
    }

    @Override
    public int getInternalNodeIdOfOsmNode(long nodeOsmId) {
        return (int) nodeOsmId;
    }

    @Override
    public long getOsmIdOfInternalEdge(int edgeId) {
        return edgeOsmIdMap.get(edgeId);
    }

    public static interface EdgeAddedListener {

        void edgeAdded(ReaderWay way, EdgeIteratorState edge);
    }

    private void addEdge(int fromTower, int toTower, SimpleFeature road, double distance,
            GHPoint estmCentre, PointList pillarNodes) {
        EdgeIteratorState edge = graph.edge(fromTower, toTower);

        // Идентификатор OSM, он никогда не должен быть null
        long id = getOSMId(road);

        // saving from.to nodes for restrictions and edgeId
        WayNodes wayNode = new WayNodes(fromTower, toTower);
        edgeOsmIdMap.put(edge.getEdge(), id);
        wayNodesMap.put(id, wayNode);

        // Make a temporary ReaderWay object with the properties we need so we
        // can use the enocding manager
        // We (hopefully don't need the node structure on here as we're only
        // calling the flag
        // encoders, which don't use this...
        ReaderWay way = new ReaderWay(id);

        way.setTag("estimated_distance", distance);
        way.setTag("estimated_center", estmCentre);

        // Тип дороги
        Object type = road.getAttribute("fclass");
        if (type != null) {
            way.setTag("highway", type.toString());
        }

        // Максимальная скорость
        Object maxSpeed = road.getAttribute("maxspeed");
        if (maxSpeed != null && !maxSpeed.toString().trim().equals("0")) {
            way.setTag("maxspeed", maxSpeed.toString());
        }

        for (String tag : tagsToCopy) {
            Object val = road.getAttribute(tag);
            if (val != null) {
                way.setTag(tag, val);
            }
        }

        // Односторонее движение
        Object oneway = road.getAttribute("oneway");
        if (oneway != null) {
            // Geofabrik is using an odd convention for oneway field in
            // shapefile.
            // We map back to the standard convention so that tag can be dealt
            // with correctly by the flag encoder.
            String val = toLowerCase(oneway.toString().trim());
            if (val.equals("b") || val.equals("no")) {
                // в обоих направлениях
                val = "no";
            } else if (val.equals("t") || val.equals("-1")) {
                // односторонее: "Обратно направлению оцифровки"
                val = "-1";
            } else if (val.equals("f") || val.equals("yes")) {
                // односторонее: "Вперёд в направлении оцифровки"
                val = "yes";
            } else {
                val = "no";
            }

            way.setTag("oneway", val);
        }

        // Проверка доступности в Encoder
        EncodingManager.AcceptWay acceptWay = new EncodingManager.AcceptWay();
        if (!encodingManager.acceptWay(way, acceptWay)) {
            return;
        }

        IntsRef edgeFlags = encodingManager.handleWayTags(way, acceptWay, tempRelFlags);
        if (edgeFlags.isEmpty()) {
            return;
        }

        edge.setDistance(distance);
        edge.setFlags(edgeFlags);
        edge.setWayGeometry(pillarNodes);
        encodingManager.applyWayTags(way, edge);

        if (edgeAddedListeners.size() > 0) {
            // check size first so we only allocate the iterator if we have
            // listeners
            for (EdgeAddedListener l : edgeAddedListeners) {
                l.edgeAdded(way, edge);
            }
        }
    }

    private long getOSMId(SimpleFeature road) {
        long id = Long.parseLong(road.getAttribute("osm_id").toString());
        return id;
    }

    private Coordinate roundCoordinate(Coordinate c) {
        c.x = Helper.round6(c.x);
        c.y = Helper.round6(c.y);

        if (!Double.isNaN(c.z)) {
            c.z = Helper.round6(c.z);
        }

        return c;
    }

    public void addListener(EdgeAddedListener l) {
        edgeAddedListeners.add(l);
    }
}

class WayNodes {

    private int fromNode;
    private int toNode;

    WayNodes(int fromNode, int toNode) {
        this.fromNode = fromNode;
        this.toNode = toNode;
    }

    public int getFromNode() {
        return fromNode;
    }

    public int getToNode() {
        return toNode;
    }
}
