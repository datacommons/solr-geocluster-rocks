// @license magnet:?xt=urn:btih:1f739d935676111cfff4b4693e3816e664797050&dn=gpl-3.0.txt GPL-v3-or-Later
// Copyright ParIT Worker Co-operative
// Author Mark Jenkins

function show_continental_US_map(){
    // create a map in the "map" div, set the view to a given place and zoom
    var map = L.map('map', {
			attributionControl: false,
		    } );
    // fit within the boundaries of the 48 US states
    // http://en.wikipedia.org/wiki/Extreme_points_of_the_United_States
    // Southern point used is Western Dry Rocks, Florida
    // Western point used is Umatilla Reef, Washinton
    // Northern point used is Northwest Angle, Minnesota
    // Eastern point used is Sail Rock, Maine
    map.fitBounds( [
		       [24.446667, -124.785], // south-west
		       [49.384472, -66.947028] ], // north-east
		   {padding: [20, 20]}
		 );

    var markerGroup = new L.LayerGroup();
    markerGroup.addTo(map);

    var tile_layer = L.tileLayer(
	'http://{s}.mqcdn.com/tiles/1.0.0/osm/{z}/{x}/{y}.png',
	{
	    subdomains: ['otile1','otile2','otile3','otile4'],
	    maxZoom: 18, // is this needed?
	}
    );
    tile_layer.addTo(map);

    var spider_popup = new L.Popup();

    var geojson_layer_options = {
	pointToLayer: function (feature, latlng) {
	    if (feature.properties.clusterCount) {
		return L.marker(latlng,
				{icon: cluster_icon_create(
				     feature.properties.clusterCount) });
	    }
	    else {
		return L.marker(latlng);
	    }
	},

	onEachFeature: function(feature, layer) {
	    // does this feature have a property named popupContent?
	    if (feature.properties && feature.properties.popupContent
		&& ! feature.properties.clusterCount ){
		    layer.bindPopup(feature.properties.popupContent);
		}
	    else {
		layer.on('click', function(e) {
			     if (map._popup) {
				 map._popup._source.closePopup();
			     }
			     // Zoom and pan to clicked item.
			     map.panTo(layer.getLatLng());
			     map.zoomIn();
			 });
	    } // else
	}
    };


    function display_map(){
	$.getJSON("geosearch?bounds=" +
		  map.getBounds().toBBoxString() + "&zoom=" + map.getZoom(),
		  function(data, status, jqXHR){
		      var geojson_layer = L.geoJson(
			  false,
			  geojson_layer_options
		      );
		      geojson_layer.addData(data['clusters']);
		      geojson_layer.addData(data['single_points']);
		      markerGroup.clearLayers();
		      markerGroup.addLayer(geojson_layer);

		      for(var i=0; i<=data['grouped_points'].length; i++){
			  var oms = new OverlappingMarkerSpiderfier(
			      map, {keepSpiderfied: true});
			  var spidergeojson_layer = L.geoJson(
			      false, {
				  pointToLayer: function(feature, latlng){
				      new_marker = L.marker(
					  latlng,
					  {icon: L.icon(
					      {iconUrl:'spider_marker.png'} )
					   }
					  );
				      oms.addMarker(new_marker);
				      return new_marker;
				  },
			       	  onEachFeature: function(feature, layer){
				      layer.bindPopup(
					  feature.properties.popupContent);
				  }
			      }
			  );

			  spidergeojson_layer.addData(
			      data['grouped_points'][i]);
			  markerGroup.addLayer(spidergeojson_layer);
		      }

		  }
		 );
    }

    function handle_map_move_end(e){
	if (map._popup){
	    map._popup._source.closePopup();
	}
	display_map();
    }

    map.on('moveend', handle_map_move_end);
    display_map();
}

// @license-end
