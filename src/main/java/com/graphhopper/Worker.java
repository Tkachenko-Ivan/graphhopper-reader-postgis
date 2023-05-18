package com.graphhopper;

import com.graphhopper.config.Profile;
import com.graphhopper.routing.util.CarFlagEncoder;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.util.shapes.GHPoint;
import java.util.Collections;
import com.graphhopper.reader.postgis.GraphHopperPostgis;

public class Worker {

    private static final String dir = "D:\\Test\\graph";

    public static void main(String[] args) {
        GraphHopper graphHopper = new GraphHopperPostgis().forServer();
        
        EncodingManager.create(new CarFlagEncoder());

        GraphHopperConfig graphHopperConfig = new GraphHopperConfig();
        graphHopperConfig.putObject("db.host", "localhost");
        graphHopperConfig.putObject("db.port", "5432");
        graphHopperConfig.putObject("db.database", "postgres");
        graphHopperConfig.putObject("db.schema", "public");
        graphHopperConfig.putObject("db.user", "postgres");
        graphHopperConfig.putObject("db.passwd", "RoutePass");
        
        graphHopperConfig.putObject("db.tags_to_copy", "name");
        graphHopperConfig.putObject("datareader.file", "roads_view");
        
        graphHopperConfig.putObject("graph.location", dir);
        graphHopperConfig.putObject("graph.flag_encoders", "car");

        graphHopperConfig.setProfiles(Collections.singletonList(new Profile("my_car").setVehicle("car").setWeighting("fastest")));

        graphHopper.init(graphHopperConfig);
        graphHopper.importOrLoad();

        GHRequest request = new GHRequest();
        request.addPoint(new GHPoint(54.716345, 20.462178));
        request.addPoint(new GHPoint(54.720459, 20.484996));
        request.setProfile("my_car");
        
        GHResponse response = graphHopper.route(request);
        System.out.println(response.getHints());
    }

}
