/*
 * Copyright 2011 Giles Malet.
 *
 * This file is part of GRTransit.
 * 
 * GRTransit is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * GRTransit is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with GRTransit.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.kw.shrdlu.grtgtfs;

import java.util.ArrayList;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import com.google.android.maps.GeoPoint;
import com.google.android.maps.ItemizedOverlay;
import com.google.android.maps.MapView;
import com.google.android.maps.OverlayItem;
import com.google.android.maps.Projection;

public class BusstopsOverlay extends ItemizedOverlay<OverlayItem> {
	private static final String TAG = "GrtItemizedOverlay";

	private ArrayList<OverlayItem> mOverlayItems = new ArrayList<OverlayItem>();
	private Context mContext;
	private Cursor mCsr;
	private String mStopid;
	private DatabaseHelper dbHelper;
	private GeoPoint mCenter;
	public static SQLiteDatabase DB = null;
	
	// Use if no limit on stops
	public BusstopsOverlay(Drawable defaultMarker, Context context) {
		this(defaultMarker, context, null, null);
	}

	// Used to limit which stops are displayed
	public BusstopsOverlay(Drawable defaultMarker, Context context, String whereclause, String [] selectargs) {
		super(boundCenterBottom(defaultMarker));
		mContext = context;

		// Only open the DB once. Everything else uses this copy.
		if (DB == null) {
			dbHelper = new DatabaseHelper(context);
			DB = dbHelper.getReadableDatabase();
		}

		final String table = "stops";
		final String [] columns = {"stop_lat", "stop_lon", "stop_id", "stop_name"};

		// TODO - limit under debug
//		String table = "stops";
//    	if (whereclause == null) table += " limit 200";

        Cursor csr;
    	try {
    		csr = DB.query(table, columns, whereclause, selectargs, null,null,null);
    	} catch (SQLException e) {
    		Log.e(TAG, "DB query failed: " + e.getMessage());
    		return;
    	}

    	// Going to calculate our center
    	int min_lat = 360000000, min_lon = 360000000, max_lat = -360000000, max_lon = -360000000;
    	
		boolean more = csr.moveToPosition(0);
   		while (more) {
   			int stop_lat = (int)(csr.getFloat(0) * 1000000); // microdegrees
   			int stop_lon = (int)(csr.getFloat(1) * 1000000);
   			
   			if (stop_lat < min_lat)
   				min_lat = stop_lat;
   			if (stop_lat > max_lat)
   				max_lat = stop_lat;
   			if (stop_lon < min_lon)
   				min_lon = stop_lon;
   			if (stop_lon > max_lon)
   				max_lon = stop_lon;
   			
   			GeoPoint point = new GeoPoint(stop_lat, stop_lon);
   			OverlayItem overlayitem = new OverlayItem(point, csr.getString(2), csr.getString(3));
   		    mOverlayItems.add(overlayitem);
   	        more = csr.moveToNext();
   		}
   		csr.close();
   		
   		mCenter = new GeoPoint(min_lat + (max_lat-min_lat)/2, min_lon + (max_lon-min_lon)/2);
   		
		populate();	// chomps up a lot of time....
	}

	// This is used when a route number is clicked on in the dialog, after a stop is clicked.
	private DialogInterface.OnClickListener mClick = new DialogInterface.OnClickListener() {
		  public void onClick(DialogInterface dialog, int which) {
			  if (mCsr.moveToPosition(which)) {
				  String route = mCsr.getString(0);
//				  Log.v(TAG, "clicked position " + which + ": route " + route);

				  int split = route.indexOf(" - ");
				  String route_id = route.substring(0,split);
				  String headsign = route.substring(split+3);

				  Intent bustimes = new Intent(mContext, BustimesActivity.class);
				  String pkgstr = mContext.getApplicationContext().getPackageName();
				  bustimes.putExtra(pkgstr + ".route_id", route_id);
				  bustimes.putExtra(pkgstr + ".headsign", headsign);
				  bustimes.putExtra(pkgstr + ".stop_id", mStopid);
				  mContext.startActivity(bustimes);
			  }
		  }
	};

	@Override
	// Default returns `first ranked item' - WTF is that?
	public GeoPoint getCenter() {
		return mCenter;
	}
	
	// This is called when a bus stop is clicked on in the map.
	@Override
	protected boolean onTap(int index) {
	  OverlayItem item = mOverlayItems.get(index);
	  mStopid = item.getTitle();
	  
	  AlertDialog.Builder dialog = new AlertDialog.Builder(mContext);
	  dialog.setTitle("Routes using stop " + mStopid + ", " + item.getSnippet()); 

	  // Find which routes use the given stop.
	  final String table = "trips";
	  final String [] select = {"distinct route_id || \" - \" || trip_headsign as _id"};
	  final String where = "trip_id in (select trip_id from stop_times where stop_id = ?)";
	  final String [] selectargs = {item.getTitle()};
	  mCsr = DB.query(table, select, where, selectargs, null,null,null);

	  dialog.setCursor(mCsr, mClick, "_id");
	  dialog.show();
	  return true;
	}

	@Override
	protected OverlayItem createItem(int i) {
	  return mOverlayItems.get(i);
	}
	
	@Override
	public int size() {
		return mOverlayItems.size();
	}

	/*
	 * Override this so we can add text labels to each pin.
	 * @see com.google.android.maps.ItemizedOverlay#draw(android.graphics.Canvas, com.google.android.maps.MapView, boolean)
	 */
	@Override
	public void draw(Canvas canvas, MapView view, boolean shadow) {
		super.draw(canvas, view, shadow);
		
		if (shadow || view.getZoomLevel() <= 15)
			return;
		
//		Log.v(TAG, "draw " + shadow + ", zoom is " + view.getZoomLevel());
		
        //Paint
		Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(20);
        paint.setARGB(228,0,64,224);

		// Convert geo points to points on the canvas
		Projection proj = view.getProjection();
		Point pt_scr = new Point();
		GeoPoint pt_geo;
		
		for (int i=0; i<mOverlayItems.size(); i++) {
			OverlayItem item = mOverlayItems.get(i);
			pt_geo = item.getPoint();
			proj.toPixels(pt_geo, pt_scr);
			
            //show text to the right of the icon
            canvas.drawText(item.getTitle(), pt_scr.x, pt_scr.y+12, paint);
            if (view.getZoomLevel() >= 18)
            	canvas.drawText(item.getSnippet(), pt_scr.x, pt_scr.y-15, paint);
		}
	}
}
