package com.graphhopper.resources;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.jackson.MultiException;
import com.graphhopper.jackson.ResponsePathSerializer;
import com.graphhopper.routing.ProfileResolver;
import com.graphhopper.util.*;
import com.graphhopper.util.shapes.GHPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.validation.constraints.NotNull;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.util.List;

import static com.graphhopper.util.Parameters.Routing.*;

import java.util.Arrays;
import java.util.Collections;


@Path("tsp")
public class TSPResource extends RouteResource{
    private static final Logger logger = LoggerFactory.getLogger(RouteResource.class);

    @Inject
    public TSPResource(GraphHopper graphHopper, ProfileResolver profileResolver, @Named("hasElevation") Boolean hasElevation) {
        super(graphHopper, profileResolver, hasElevation);
    }
    @Override
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response doPost(@NotNull GHRequest request, @Context HttpServletRequest httpReq) {
        StopWatch sw = new StopWatch().start();
        if (request.getCustomModel() == null) {
            if (Helper.isEmpty(request.getProfile())) {
                // legacy parameter resolution (only used when there is no custom model)
                enableEdgeBasedIfThereAreCurbsides(request.getCurbsides(), request);
                request.setProfile(profileResolver.resolveProfile(request.getHints()).getName());
                removeLegacyParameters(request.getHints());
            }
        } else {
            if (Helper.isEmpty(request.getProfile()))
                // throw a dedicated exception here, otherwise a missing profile is still caught in Router
                throw new IllegalArgumentException("The 'profile' parameter is required when you use the `custom_model` parameter");
        }

        // find end point in request points
        List<GHPoint> requestPoints = request.getPoints();
        GHPoint startPoint = requestPoints.get(0);

        // size of points
        int nRequestPoints = requestPoints.size();

        // calc distance from first point to the remaining points in the list
        //initial value
        Double maxDistance = Double.MIN_VALUE;
        int endPoint = nRequestPoints - 1;


        for (int i = 1; i < nRequestPoints; i++){
                GHRequest tmpRequest = new GHRequest();
                tmpRequest.setPoints(Arrays.asList(new GHPoint[]{startPoint,requestPoints.get(i)})).
                        setProfile(request.getProfile()).
                        setAlgorithm("astar").
                        setLocale(request.getLocale()).
                        setHeadings(request.getHeadings()).
                        setCurbsides(request.getCurbsides()).
                        setSnapPreventions(request.getSnapPreventions()).
                        getHints().
                        putObject(CALC_POINTS, true).
                        putObject(INSTRUCTIONS, false);
        
                GHResponse ghResponse = graphHopper.route(tmpRequest);
                if (ghResponse.hasErrors()) {
                    logger.error("Calc distance from first point to the remaining points in the list, errors:" + ghResponse.getErrors());
                } else {
                    Double currentDistance = ghResponse.getBest().getDistance();
                    if (currentDistance > maxDistance) {
                        maxDistance = currentDistance;
                        endPoint = i;
                    }
                }
        }
        
        // swap point has max distance to end list
        Collections.swap(requestPoints, endPoint, nRequestPoints -1);

        // set again point
        request.setPoints(requestPoints);

        errorIfLegacyParameters(request.getHints());
        GHResponse ghResponse = graphHopper.route(request);
        boolean instructions = request.getHints().getBool(INSTRUCTIONS, true);
        boolean enableElevation = request.getHints().getBool("elevation", false);
        boolean calcPoints = request.getHints().getBool(CALC_POINTS, true);
        boolean pointsEncoded = request.getHints().getBool("points_encoded", true);

        long took = sw.stop().getNanos() / 1_000_000;
        String infoStr = httpReq.getRemoteAddr() + " " + httpReq.getLocale() + " " + httpReq.getHeader("User-Agent");
        String queryString = httpReq.getQueryString() == null ? "" : (httpReq.getQueryString() + " ");
        // todo: vehicle/weighting will always be empty at this point...
        String weightingVehicleLogStr = "weighting: " + request.getHints().getString("weighting", "")
                + ", vehicle: " + request.getHints().getString("vehicle", "");
        String logStr = queryString + infoStr + " " + request.getPoints().size() + ", took: "
                + String.format("%.1f", (double) took) + " ms, algo: " + request.getAlgorithm() + ", profile: " + request.getProfile()
                + ", " + weightingVehicleLogStr
                + ", custom_model: " + request.getCustomModel();

        if (ghResponse.hasErrors()) {
            logger.error(logStr + ", errors:" + ghResponse.getErrors());
            throw new MultiException(ghResponse.getErrors());
        } else {
            logger.info(logStr + ", alternatives: " + ghResponse.getAll().size()
                    + ", distance0: " + ghResponse.getBest().getDistance()
                    + ", weight0: " + ghResponse.getBest().getRouteWeight()
                    + ", time0: " + Math.round(ghResponse.getBest().getTime() / 60000f) + "min"
                    + ", points0: " + ghResponse.getBest().getPoints().size()
                    + ", debugInfo: " + ghResponse.getDebugInfo());
            return Response.ok(ResponsePathSerializer.jsonObject(ghResponse, instructions, calcPoints, enableElevation, pointsEncoded, took)).
                    header("X-GH-Took", "" + Math.round(took)).
                    type(MediaType.APPLICATION_JSON).
                    build();
        }
    }
}
