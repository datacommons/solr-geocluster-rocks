package ca.markjenkins.geoclusterrocks;

import org.apache.wicket.markup.html.WebPage;
import org.apache.wicket.request.handler.TextRequestHandler;
import org.apache.wicket.request.mapper.parameter.PageParameters;
import org.apache.wicket.request.cycle.RequestCycle;

import org.geojson.FeatureCollection;
import org.geojson.Feature;
import org.geojson.Point;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

import com.spatial4j.core.io.GeohashUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrServer;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrQuery.SortClause;

import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.params.GroupParams;
import org.apache.solr.common.params.StatsParams;

import com.github.davidmoten.geo.GeoHash;
import com.github.davidmoten.geo.LatLong;

import java.util.Set;
import java.util.Map.Entry;
import java.util.List;
import java.util.HashMap;
import java.util.Iterator;

public class GeoSearch extends WebPage {
    static final int STATS_MEAN_FIELD = 6;

    static final SolrServer solr;
    static {
	solr = new HttpSolrServer( "http://localhost:8080/solr" );
    }

    static final String GROUP_LIMIT = "1";

    // this is pretty much strait lifted from
    // http://cgit.drupalcode.org/geocluster/tree/includes/GeoclusterHelper.inc
    static final int ZOOMS = 30+1;
    // // Meters per pixel.
    // $maxResolution = 156543.03390625;
    //static final int TILE_SIZE = 256;
    //maxResolution = GEOFIELD_KILOMETERS * 1000 / $tile_size;
    static final double MAX_RESOLUTION = 156.412 * 1000;

    static final int[] geohash_lengths_for_zooms;
    static {
	geohash_lengths_for_zooms = new int[ZOOMS];
	for( int zoom=0; zoom < ZOOMS; zoom++ ){
	    geohash_lengths_for_zooms[zoom] =
		lengthFromDistance(MAX_RESOLUTION / Math.pow(2, zoom) );
	}
    }

    static final int GEOCLUSTER_DEFAULT_DISTANCE = 65;

    static final double RAD_TO_DEGREES = 180 / Math.PI;
    static final int EARTH_AREA = 6378137;
    /**
     * Convert from meters (Spherical Mercator) to degrees (EPSG:4326).
     *
     * This is based on
http://cgit.drupalcode.org/geocluster/tree/includes/GeoclusterHelper.inc
     * 
     * which is based on
https://github.com/mapbox/clustr/blob/gh-pages/src/clustr.js
     *
     *  also see
http://dev.openlayers.org/docs/files/OpenLayers/Layer/SphericalMercator-js.html
https://github.com/openlayers/openlayers/blob/master/lib/OpenLayers/Projection.js#L278
    *
    * @return (lon, lat)
    */
    static double[] backwardMercator(double x, double y) {
	double[] result = new double[2];
	result[0] = x * RAD_TO_DEGREES / EARTH_AREA;
	result[1] =
	    ((Math.PI * 0.5) - 2.0 *
	     Math.atan(Math.exp(-y / EARTH_AREA))
	     ) * RAD_TO_DEGREES;
	return result;
    }
    /**
     * Calculate geohash length for clustering by a specified distance
     * in pixels. This is based on lengthFromDistance() from 
http://cgit.drupalcode.org/geocluster/tree/includes/GeohashHelper.inc
     */
    static int lengthFromDistance(double resolution) {
        double cluster_distance_meters =
	    GEOCLUSTER_DEFAULT_DISTANCE * resolution;
        double x = cluster_distance_meters;
	double y = cluster_distance_meters;
	double[] width_height = backwardMercator(x, y);

        int hashLen =
            GeohashUtils.lookupHashLenForWidthHeight(width_height[0],
						     width_height[1] );
        return hashLen +1;
    }

    static Logger log_l4 = LoggerFactory.getLogger( GeoSearch.class );

    public static String restrictLatitude(String latitude){
	try {
	    double latitude_double = Double.parseDouble(latitude);
	    if (-90 > latitude_double)
		return "-90";
	    else if (latitude_double > 90 )
		return "90";
	    else
		return latitude;
	}
	catch (NumberFormatException e){
	    if (latitude.charAt(0) == '-')
		return "-90";
	    else
		return "90";
	}
    }

    public static String restrictLongitude(String longitude){
	try {
	    double longitude_double = Double.parseDouble(longitude);
	    if (-180 > longitude_double)
		return "-180";
	    else if (longitude_double > 90 )
		return "180";
	    else
		return longitude;
	}
	catch (NumberFormatException e){
	    if (longitude.charAt(0) == '-')
		return "-180";
	    else
		return "180";
	}
    }

    public static QueryResponse query_locations_in_solr
	(String bounds, int zoom, boolean stats_enabled){
	QueryResponse rsp = null;
	SolrQuery params = new SolrQuery();
	String bot_left_long;
	String bot_left_lat;
	String top_right_long;
	String top_right_lat;

	if (bounds == null){
	    bot_left_long = "-180";
	    bot_left_lat = "-90";
	    top_right_long = "180";
	    top_right_lat = "90";
	    params.setQuery("*:*");
	}
	else {
	    String[] queryBounds = bounds.split(",");
	    bot_left_long = restrictLongitude(queryBounds[0]);
	    bot_left_lat = restrictLatitude(queryBounds[1]);
	    top_right_long = restrictLongitude(queryBounds[2]);
	    top_right_lat = restrictLatitude(queryBounds[3]);

	    params.setQuery("location:[" +
			    bot_left_lat + "," +
			    bot_left_long +
			    " TO " + 
			    top_right_lat + "," +
			    top_right_long + "]");
	}

	int hash_len = geohash_lengths_for_zooms[zoom];
	String hash_len_geohash_field = "geohash_" + hash_len;

	params.addSort(SortClause.asc(hash_len_geohash_field));

	params.setParam(GroupParams.GROUP, true);
	// this is silly, should take 32 to the power of hash_len
	// or not.. that makes a huge number when hash_len is 12...
	// perhaps there is some theoritical upper limit on the number
	// or we just set 
	Set<String> real_prefix_hashes = GeoHash.coverBoundingBox
	    (Double.parseDouble(top_right_lat), // topLeftLat
	     Double.parseDouble(bot_left_long), // topLeftLon
	     Double.parseDouble(bot_left_lat), // bottomRightLat
	     Double.parseDouble(top_right_long), // bottomRightLon
	     hash_len).getHashes();
	params.setRows(real_prefix_hashes.size());
	params.setParam(GroupParams.GROUP_LIMIT, GROUP_LIMIT);
	params.setParam(GroupParams.GROUP_FIELD, hash_len_geohash_field);

	if (stats_enabled){
	    params.setParam(StatsParams.STATS, true);
	    params.setParam(StatsParams.STATS_FIELD, "longitude", "latitude");
	    params.setParam(StatsParams.STATS_FACET, hash_len_geohash_field);
	}

	try {
	    rsp = solr.query( params );
	}
	catch (SolrServerException ex) {
	    log_l4.warn( "unable to execute query", ex );
	    return rsp;
	}

	return rsp;
    }

    static HashMap<String, Point> getClusterStatistics
	(NamedList<Object> solr_response){

	HashMap<String, Point> center_of_location =
	    new HashMap<String, Point>();

	// for each statistic, there is only one field (geohash_?)
	// we have facetted on, hence the getVal(0)
	NamedList<Object> stat_latitudes = (NamedList<Object>)
	    ( (NamedList<Object> )
	      solr_response.findRecursive("stats", "stats_fields",
					  "latitude", "facets") ).getVal(0);
	NamedList<Object> stat_longitudes = (NamedList<Object>)
	    ( (NamedList<Object>)
	      solr_response.findRecursive("stats", "stats_fields",
					  "longitude", "facets")).getVal(0);

	Iterator<Entry<String, Object>> lat_iter =
	    stat_latitudes.iterator();
	Iterator<Entry<String, Object>> long_iter =
	    stat_longitudes.iterator();

	while (lat_iter.hasNext() && long_iter.hasNext()){
	    Entry<String, Object> lat_entry = lat_iter.next();
	    Entry<String, Object> long_entry = long_iter.next();

	    // this is the crucial assumption here, that our two
	    // statistics are provided in the same hash order!
	    assert( lat_entry.getKey() == long_entry.getKey() );

	    NamedList<Double> long_stats =
		(NamedList<Double>)long_entry.getValue();
	    assert( long_stats.getName(STATS_MEAN_FIELD).equals("mean") );

	    center_of_location.put( lat_entry.getKey(), new Point(
		long_stats.getVal(STATS_MEAN_FIELD),
		((NamedList<Double>)lat_entry.getValue())
		.getVal(STATS_MEAN_FIELD)
	    ) );
	}
	return center_of_location;
    }

    public GeoSearch(PageParameters pageParameters) {
	RequestCycle cy = getRequestCycle();

	int zoom;
	try {
	    zoom = Integer.parseInt
		( cy.getRequest().getQueryParameters()
		  .getParameterValue("zoom").toString() );
	}
	catch (NumberFormatException e){
	    log_l4.error(e.toString());
	    zoom = 0;
	}

	String stats =
	    cy.getRequest().getQueryParameters()
	    .getParameterValue("stats").toString();
	boolean stats_enabled =
	    stats == null || stats.equals("null") || stats.equals("true");

	QueryResponse rsp = query_locations_in_solr(
	    cy.getRequest().getQueryParameters().getParameterValue("bounds")
	    .toString(),
	    zoom,
            stats_enabled );

	if (rsp == null){
	    cy.scheduleRequestHandlerAfterCurrent
		( new TextRequestHandler("application/json", null, "{}" ) );
	    return;
	}
	
	FeatureCollection fc = new FeatureCollection();

	NamedList<Object> solr_response = rsp.getResponse();

	HashMap<String, Point> center_of_location = null;

	if (stats_enabled){
	    center_of_location = getClusterStatistics(solr_response);
	}

	// we know there is only one field we're grouping on, hence getVal(0)
	NamedList<Object> groups_f_field = (NamedList<Object>)
	    ((NamedList<Object>)solr_response.get("grouped")).getVal(0);

	for (NamedList<Object> group:
		 (List<NamedList<Object>>)groups_f_field.get("groups") ){
	    SolrDocumentList docs =
		(SolrDocumentList)group.get("doclist");

	    if (docs.getNumFound() == 1 ){
		SolrDocument doc = docs.get(0);
		Feature f = new Feature();
		f.setProperty("popupContent",
			      (String) doc.getFirstValue("name") );
		String location = (String)doc.getFirstValue("location");
		String[] location_parts = location.split(", ");
		Point p = new Point(
				    Double.parseDouble(location_parts[1]),
				    Double.parseDouble(location_parts[0]) );
		f.setGeometry(p);
		fc.add(f);
	    }
	    else if (docs.getNumFound() > 1) {
		String hash_prefix = (String)group.get("groupValue");
		Feature f = new Feature();
		f.setProperty("clusterCount", docs.getNumFound() );
		if (center_of_location == null ||
		    ! center_of_location.containsKey(hash_prefix) ||
		    center_of_location.get(hash_prefix) == null
		    ){
		    LatLong lat_long = GeoHash.decodeHash(hash_prefix);
		    f.setGeometry(new Point( lat_long.getLon(),
					     lat_long.getLat()
					     ) );
		}
		else
		    f.setGeometry(center_of_location.get(hash_prefix));

		fc.add(f);
	    }
	}
	String json_output = "{}";
	try {
	    json_output = new ObjectMapper().writeValueAsString(fc);
	}
	catch (JsonProcessingException e) {
	    // we're forced to catch this, but I don't think we'll ever see it
	    log_l4.error("JsonProblem we would never expect", e);
	}


	cy.scheduleRequestHandlerAfterCurrent
	    ( new TextRequestHandler("application/json", null,
				     json_output ) );
    }
}
