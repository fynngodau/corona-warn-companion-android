/*
 * Corona-Warn-Companion. An app that shows COVID-19 Exposure Notifications details.
 * Copyright (C) 2020  Michael Huebler <corona-warn-companion@tosl.org> and other contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.tosl.coronawarncompanion.matchentries;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.AxisBase;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.CopyrightOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.views.CustomZoomButtonsController.Visibility;

import org.tosl.coronawarncompanion.Country;
import org.tosl.coronawarncompanion.R;
import org.tosl.coronawarncompanion.diagnosiskeys.DiagnosisKey;
import org.tosl.coronawarncompanion.diagnosiskeys.DiagnosisKeysProtos;
import org.tosl.coronawarncompanion.gmsreadout.ContactRecordsProtos;
import org.tosl.coronawarncompanion.matchentries.MatchEntryContent.DailyMatchEntries;
import org.tosl.coronawarncompanion.matcher.Matcher;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import static org.tosl.coronawarncompanion.tools.Utils.byteArrayToHexString;
import static org.tosl.coronawarncompanion.tools.Utils.getMillisFromENIN;
import static org.tosl.coronawarncompanion.tools.Utils.getMillisFromSeconds;
import static org.tosl.coronawarncompanion.tools.Utils.resolveColorAttr;
import static org.tosl.coronawarncompanion.tools.Utils.standardRollingPeriod;
import static org.tosl.coronawarncompanion.tools.Utils.xorTwoByteArrays;

/**
 * {@link RecyclerView.Adapter} that can display a {@link org.tosl.coronawarncompanion.matcher.Matcher.MatchEntry}.
 */
public class MatchesRecyclerViewAdapter extends RecyclerView.Adapter<MatchesRecyclerViewAdapter.ViewHolder> {

    private static final String TAG = "MatchesRVAdapter";
    private final int lineColor;
    private static final int redColor = Color.parseColor("#FF0000");
    private static final int orangeColor = Color.parseColor("#FFA500");
    private static final int yellowColor = Color.parseColor("#FFFF00");
    private static final int greenColor = Color.parseColor("#00FF00");
    private final float textScalingFactor;

    private final ArrayList<DkAndMatchEntries> mValues;
    private final boolean locationDataAvailable;
    private final Context mContext;

    private boolean showAllScans = false;
    private boolean mapDisplayAllowed = false;

    public MatchesRecyclerViewAdapter(DailyMatchEntries dailyMatchEntries, boolean locationDataAvailable, Context context) {
        this.mContext = context;
        this.mValues = new ArrayList<>();
        for (Map.Entry<DiagnosisKey, MatchEntryContent.GroupedByDkMatchEntries> entry :
                dailyMatchEntries.getMap().entrySet()) {
            mValues.add(new DkAndMatchEntries(entry.getKey(), entry.getValue()));
        }
        Collections.sort(mValues);
        this.locationDataAvailable = locationDataAvailable;
        DisplayMetrics metrics = this.mContext.getResources().getDisplayMetrics();
        this.textScalingFactor = metrics.scaledDensity/metrics.density;
        this.lineColor = resolveColorAttr(android.R.attr.textColorSecondary, context);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.match_card_fragment, parent, false);
        return new ViewHolder(view, this.locationDataAvailable && this.mapDisplayAllowed, this.mContext);
    }

    @Override
    public void onViewDetachedFromWindow(ViewHolder holder) {
        super.onViewDetachedFromWindow(holder);

        holder.mMapView.onPause();
    }

    @Override
    public void onViewAttachedToWindow(ViewHolder holder) {
        super.onViewAttachedToWindow(holder);

        holder.mMapView.onResume();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBindViewHolder(final ViewHolder holder, int position) {
        holder.mDkAndMatchEntries = mValues.get(position);
        DiagnosisKey dk = holder.mDkAndMatchEntries.dk;
        MatchEntryContent.GroupedByDkMatchEntries groupedByDkMatchEntries = holder.mDkAndMatchEntries.matchEntries;

        ArrayList<Matcher.MatchEntry> list = groupedByDkMatchEntries.getList();

        // Text Views:

        // set date label formatter
        String deviceDateFormat = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "Hm");
        DateFormat dateFormat = new SimpleDateFormat(deviceDateFormat, Locale.getDefault());
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        // UTC because we don't want DateFormat to do additional time zone compensation

        boolean hasTransmissionRiskLevel = false;
        boolean hasAustrianColorCode = false;
        String austrianColorCode = "";
        int transmissionRiskLevel = 0;
        if (dk.dk.hasTransmissionRiskLevel()) {
            if (dk.countryCode.equals(Country.Austria.getCode(mContext))) {
                // Austria
                if (dk.dk.getTransmissionRiskLevel() == 5) {
                    hasAustrianColorCode = true;
                    austrianColorCode = mContext.getString(R.string.austrian_color_code_yellow);
                } else
                if (dk.dk.getTransmissionRiskLevel() == 2) {
                    hasAustrianColorCode = true;
                    austrianColorCode = mContext.getString(R.string.austrian_color_code_red);
                }
            } else if (dk.countryCode.equals(Country.Switzerland.getCode(mContext))) {
                // Switzerland
                transmissionRiskLevel = dk.dk.getTransmissionRiskLevel();
                if (transmissionRiskLevel != 0) {
                    hasTransmissionRiskLevel = true;
                }
                // note that TRL is usually 0 - then it is not shown
            } else if (dk.countryCode.equals(Country.Canada.getCode(mContext))) {
                // transmission risk level is always set to 1
                hasTransmissionRiskLevel = false;
            } else {
                // all other countries
                transmissionRiskLevel = dk.dk.getTransmissionRiskLevel();
                hasTransmissionRiskLevel = true;
            }
        }
        boolean hasReportType = false;
        DiagnosisKeysProtos.TemporaryExposureKey.ReportType reportType = DiagnosisKeysProtos.TemporaryExposureKey.ReportType.UNKNOWN;
        if (dk.dk.hasReportType()) {
            reportType = dk.dk.getReportType();
            hasReportType = true;
        }
        boolean hasDaysSinceOnsetOfSymptoms = false;
        int daysSinceOnsetOfSymptoms = 0;
        if (dk.dk.hasDaysSinceOnsetOfSymptoms()) {
            daysSinceOnsetOfSymptoms = dk.dk.getDaysSinceOnsetOfSymptoms();
            hasDaysSinceOnsetOfSymptoms = true;
        }
        if ((daysSinceOnsetOfSymptoms == 0) && dk.countryCode.equals(Country.Germany.getCode(mContext))) {
            // Workaround, because the German CWA server publishes DSOS == 0 for all keys.
            // DSOS == 0 would be a very high risk, but it's also very unlikely that users get their test result back
            // on the day when their symptoms started. So we suppress this for now.
            hasDaysSinceOnsetOfSymptoms = false;
        }

        //Log.d(TAG, "Old TimeZoneOffset: " + CWCApplication.getTimeZoneOffsetSeconds());
        //Log.d(TAG, "New TimeZoneOffset: " + TimeZone.getDefault().
        //        getOffset(getMillisFromENIN(dk.dk.getRollingStartIntervalNumber()+standardRollingPeriod/2)) / 1000);
        MatchEntryDetails matchEntryDetails = getMatchEntryDetails(list,
                TimeZone.getDefault().
                        getOffset(getMillisFromENIN(dk.dk.getRollingStartIntervalNumber()+standardRollingPeriod/2)) / 1000);
        int minTimestampLocalTZDay0 = matchEntryDetails.minTimestampLocalTZDay0;
        int maxTimestampLocalTZDay0 = matchEntryDetails.maxTimestampLocalTZDay0;

        Date startDate = new Date(getMillisFromSeconds(minTimestampLocalTZDay0));
        Date endDate = new Date(getMillisFromSeconds(maxTimestampLocalTZDay0));
        String startDateStr = dateFormat.format(startDate);
        String endDateStr = dateFormat.format(endDate);

        StringBuilder sb;

        // text in the upper right corner
        sb = new StringBuilder();
        sb.append("\n");
        int numLinesDetails = 1;
        if (hasReportType) {
            sb.append(this.mContext.getResources().getString(R.string.report_type)).append(": ").append(getReportTypeStr(reportType)).append("\n");
            numLinesDetails++;
        }
        if (hasTransmissionRiskLevel) {
            sb.append(this.mContext.getResources().getString(R.string.transmission_risk_level)).append(": ").append(transmissionRiskLevel).append("\n");
            numLinesDetails++;
        }
        if (hasAustrianColorCode) {
            sb.append(this.mContext.getResources().getString(R.string.transmission_risk_level)).append(": ").append(austrianColorCode).append("\n");
            numLinesDetails++;
        }
        if (hasDaysSinceOnsetOfSymptoms) {
            sb.append(this.mContext.getResources().getString(R.string.days_since_onset_of_symptoms)).append(": ").append(daysSinceOnsetOfSymptoms).append("\n");
            numLinesDetails++;
        }
        holder.mTextViewRiskDetails.setText(sb.toString());

        // main text
        sb = new StringBuilder();
        sb.append(this.mContext.getResources().getString(R.string.time)).append(" ");
        if (startDateStr.equals(endDateStr)) {
            sb.append(startDateStr).append("\n");
        } else {
            sb.append(startDateStr).append("-").append(endDateStr).append("\n");
        }
        // text += "("+byteArrayToHexString(dk.getKeyData().toByteArray())+")\n";
        for (int i = 1; i < numLinesDetails; i++) {
            sb.append("\n");
        }
        sb.append("\n").append(this.mContext.getResources().getString(R.string.distance_shown_as_attenuation)).append(":");
        holder.mTextViewMainText.setText(sb.toString());

        // Graph:
        configureDetailsChart(holder.mChartView, matchEntryDetails.dataPointsMinAttenuation,
                matchEntryDetails.dotColorsMinAttenuation,
                matchEntryDetails.dataPoints, matchEntryDetails.dotColors,
                minTimestampLocalTZDay0, maxTimestampLocalTZDay0,
                matchEntryDetails.minAttenuation, matchEntryDetails.maxAttenuation,
                mContext);
        holder.mChartView.getLineData().getDataSetByIndex(1).setVisible(this.showAllScans);

        if (this.locationDataAvailable && this.mapDisplayAllowed &&
                matchEntryDetails.latitude != 0.0 && matchEntryDetails.longitude != 0.0) {
            // this test means that we will not show valid locations if they are _exactly_ on equator and/or
            // _exactly_ on prime meridian, but that should be rare enough so that we can accept it.
            MapController mapController = (MapController) holder.mMapView.getController();
            mapController.setZoom(17);
            GeoPoint point = new GeoPoint(matchEntryDetails.latitude, matchEntryDetails.longitude);
            Marker marker = new Marker(holder.mMapView);
            marker.setPosition(point);
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            marker.setTitle(this.mContext.getResources().getString(R.string.point_of_exposure));
            holder.mMapView.setVisibility(View.VISIBLE);
            holder.mMapView.setExpectedCenter(point);
            holder.mMapView.getOverlays().add(marker);
            holder.mMapView.invalidate();
        }
        else {
            // showing a default map without a marker would cause confusion
            holder.mMapView.setVisibility(View.GONE);
        }

        if (this.showAllScans) {
            String txPowerStr;
            if (matchEntryDetails.minTxPower == matchEntryDetails.maxTxPower) {
                txPowerStr = String.valueOf(matchEntryDetails.minTxPower);
            } else {
                txPowerStr = String.valueOf(matchEntryDetails.minTxPower) + ".." +
                        String.valueOf(matchEntryDetails.maxTxPower);
            }
            holder.mTextViewTxPower.setText(this.mContext.getResources().getString(R.string.tx_power, txPowerStr));
            ViewGroup.LayoutParams params = holder.mTextViewTxPower.getLayoutParams();
            params.height = this.mContext.getResources().getDimensionPixelSize(R.dimen.details_text_view_3_height);
            holder.mTextViewTxPower.setLayoutParams(params);
        } else {
            holder.mTextViewTxPower.setText("");
            ViewGroup.LayoutParams params = holder.mTextViewTxPower.getLayoutParams();
            params.height = this.mContext.getResources().getDimensionPixelSize(R.dimen.details_text_view_3_height_disabled);
            holder.mTextViewTxPower.setLayoutParams(params);
        }
    }

    public static class MatchEntryDetails {
        public ArrayList<Entry> dataPoints;
        public ArrayList<Integer> dotColors;
        public ArrayList<Entry> dataPointsMinAttenuation;
        public ArrayList<Integer> dotColorsMinAttenuation;
        public int minAttenuation;
        public int maxAttenuation;
        public byte minTxPower;
        public byte maxTxPower;
        public int minTimestampLocalTZDay0;
        public int maxTimestampLocalTZDay0;
        public double longitude;
        public double latitude;
        public MatchEntryDetails() {
            this.dataPoints = new ArrayList<>();
            this.dotColors = new ArrayList<>();
        }
    }

    public static MatchEntryDetails getMatchEntryDetails(ArrayList<Matcher.MatchEntry> list,
                                                         int timeZoneOffset) {
        // Get the details for one risk encounter.

        // Threshold value for break detection:
        final int pauseThresholdSeconds = 10;

        MatchEntryDetails result = new MatchEntryDetails();
        int minTimestampLocalTZ = Integer.MAX_VALUE;
        int maxTimestampLocalTZ = Integer.MIN_VALUE;
        result.dataPoints = new ArrayList<>();
        result.dotColors = new ArrayList<>();
        result.dataPointsMinAttenuation = new ArrayList<>();
        result.dotColorsMinAttenuation = new ArrayList<>();
        result.minAttenuation = Integer.MAX_VALUE;
        result.maxAttenuation = Integer.MIN_VALUE;
        result.minTxPower = Byte.MAX_VALUE;
        result.maxTxPower = Byte.MIN_VALUE;
        result.longitude = 0.0;
        result.latitude = 0.0;

        class InterimDataPoint implements Comparable<InterimDataPoint> {
            public final Integer timestampLocalTZ;
            public final Integer attenuation;

            private InterimDataPoint(int timestampLocalTZ, int attenuation) {
                this.timestampLocalTZ = timestampLocalTZ;
                this.attenuation = attenuation;
            }

            @Override
            public int compareTo(InterimDataPoint o) {
                return timestampLocalTZ.compareTo(o.timestampLocalTZ);
            }
        }

        ArrayList<InterimDataPoint> dataPointsInterimList = new ArrayList<>();

        // First step: Create a "flat" sorted list from all scan records from all matchEntries
        for (Matcher.MatchEntry matchEntry : list) {  // process each matchEntry separately
            for (ContactRecordsProtos.ScanRecord scanRecord : matchEntry.contactRecords.getRecordList()) {
                // check whether the GMS database contains the "old" or the "new" format:
                // (up to now, only the "old" / deprecated version was seen in the wild)
                if (scanRecord.hasRssiMultipleValues() && scanRecord.getRssiMultipleValues().size() > 0 &&
                        scanRecord.getAemMultipleValuesCount() == scanRecord.getRssiMultipleValues().size()) {
                    // new format
                    for (int pos = 0; pos < scanRecord.getRssiMultipleValues().size(); pos++) {
                        byte[] aem = xorTwoByteArrays(scanRecord.getAemMultipleValues(pos).toByteArray(), matchEntry.aemXorBytes);
                        if ((aem[2] != 0x00) || (aem[3] != 0x00)) {
                            Log.w(TAG, "WARNING: Apparently invalid AEM: " + byteArrayToHexString(aem));
                        }
                        byte txPower = aem[1];
                        //Log.d(TAG, "TXPower: "+txPower+" dBm");
                        byte rssi = scanRecord.getRssiMultipleValues().toByteArray()[pos];
                        //Log.d(TAG, "RSSI: "+rssi+" dBm");
                        int attenuation = txPower - rssi;
                        //Log.d(TAG, "Attenuation: "+attenuation+" dB");

                        int timestampLocalTZ = scanRecord.getTimestamp() + timeZoneOffset;

                        // store to temporary buffers:
                        dataPointsInterimList.add(new InterimDataPoint(timestampLocalTZ, attenuation));

                        // if found, store max/min values
                        if (result.minTxPower > txPower) {
                            result.minTxPower = txPower;
                        }
                        if (result.maxTxPower < txPower) {
                            result.maxTxPower = txPower;
                        }
                        if (result.minAttenuation > attenuation) {
                            result.minAttenuation = attenuation;
                        }
                        if (result.maxAttenuation < attenuation) {
                            result.maxAttenuation = attenuation;
                        }
                        if (minTimestampLocalTZ > timestampLocalTZ) {
                            minTimestampLocalTZ = timestampLocalTZ;
                        }
                        if (maxTimestampLocalTZ < timestampLocalTZ) {
                            maxTimestampLocalTZ = timestampLocalTZ;
                        }
                    }
                } else //noinspection deprecation
                    if (scanRecord.hasRssi() && scanRecord.hasAem()) {
                    // old format
                    @SuppressWarnings("deprecation") byte[] aem = xorTwoByteArrays(scanRecord.getAem().toByteArray(), matchEntry.aemXorBytes);
                    if ((aem[2] != 0x00) || (aem[3] != 0x00)) {
                        Log.w(TAG, "WARNING: Apparently invalid AEM: " + byteArrayToHexString(aem));
                    }
                    byte txPower = aem[1];
                    //Log.d(TAG, "TXPower: "+txPower+" dBm");
                    @SuppressWarnings("deprecation") int rssi = scanRecord.getRssi();
                    //Log.d(TAG, "RSSI: "+rssi+" dBm");
                    int attenuation = txPower - rssi;
                    //Log.d(TAG, "Attenuation: "+attenuation+" dB");

                    int timestampLocalTZ = scanRecord.getTimestamp() + timeZoneOffset;

                    // store to temporary buffers:
                    dataPointsInterimList.add(new InterimDataPoint(timestampLocalTZ, attenuation));

                    // if found, store max/min values
                    if (result.minTxPower > txPower) {
                        result.minTxPower = txPower;
                    }
                    if (result.maxTxPower < txPower) {
                        result.maxTxPower = txPower;
                    }
                    if (result.minAttenuation > attenuation) {
                        result.minAttenuation = attenuation;
                    }
                    if (result.maxAttenuation < attenuation) {
                        result.maxAttenuation = attenuation;
                    }
                    if (minTimestampLocalTZ > timestampLocalTZ) {
                        minTimestampLocalTZ = timestampLocalTZ;
                    }
                    if (maxTimestampLocalTZ < timestampLocalTZ) {
                        maxTimestampLocalTZ = timestampLocalTZ;
                    }
                } else {
                    // unrecognized format
                    Log.e(TAG, "ERROR: Found invalid RPI ProtoBuf data.");
                }
                // if available, update location
                // (this means that we will simply show the last location that we found in this process)
                if (scanRecord.hasLatitude()) {
                    result.latitude = scanRecord.getLatitude();
                }
                if (scanRecord.hasLongitude()) {
                    result.longitude = scanRecord.getLongitude();
                }
            }
        }
        Collections.sort(dataPointsInterimList);

        // reduce timestamp to "day0", to improve resolution within the float x value of the graph:
        int timestampMinOffset= (minTimestampLocalTZ / (24*3600)) * (24*3600);
        result.minTimestampLocalTZDay0 = minTimestampLocalTZ - timestampMinOffset;
        result.maxTimestampLocalTZDay0 = maxTimestampLocalTZ - timestampMinOffset;

        // Second step: Process each scan record, group them, find the minimum attenuation in each group
        ArrayList<Entry> dataPointsBuffer = new ArrayList<>();
        ArrayList<Integer> dotColorsBuffer = new ArrayList<>();
        int lastTimestampLocalTZDay0 = 0;
        int localMinAttenuation = Integer.MAX_VALUE;

        int numLastScanRecord = dataPointsInterimList.size() - 1;
        int i = 0;
        for (InterimDataPoint interimDataPoint : dataPointsInterimList) {
            // iterate over sorted ArrayList
            int timestampLocalTZDay0 = interimDataPoint.timestampLocalTZ - timestampMinOffset;
            int attenuation = interimDataPoint.attenuation;

            // Second step: look for a break (>= pauseThresholdSeconds)
            // suppress break detection at the very first entry
            if ((i != 0) && (timestampLocalTZDay0 >= lastTimestampLocalTZDay0 + pauseThresholdSeconds)) {
                /*
                String deviceDateFormat = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "Hms");
                DateFormat dateFormat = new SimpleDateFormat(deviceDateFormat, Locale.getDefault());
                dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
                // UTC because we don't want DateFormat to do additional time zone compensation
                */
                //Log.d(TAG, "lastTimestampLocalTZDay0: "+dateFormat.format(new Date(getMillisFromSeconds((int) lastTimestampLocalTZDay0)))+
                //        ", timestampLocalTZDay0: "+dateFormat.format(new Date(getMillisFromSeconds((int) timestampLocalTZDay0))));

                // break found: Now copy the block we have collected so far
                boolean minHandled = false;
                for (int pos = 0; pos < dataPointsBuffer.size(); pos++) {
                    Entry entry = dataPointsBuffer.get(pos);
                    int color = dotColorsBuffer.get(pos);
                    //Log.d(TAG, "Entry at: "+dateFormat.format(new Date(getMillisFromSeconds((int) entry.getX()))));
                    if (!minHandled && entry.getY() <= localMinAttenuation) {
                        // This is the minimum, store in the "minimum" list
                        //Log.d(TAG, "Minimum found.");
                        result.dataPointsMinAttenuation.add(entry);
                        result.dotColorsMinAttenuation.add(color);
                        minHandled = true;
                    } else {
                        // This is one of the other entries, store in the "normal" list
                        result.dataPoints.add(entry);
                        result.dotColors.add(color);
                    }
                }
                // clear the temporary buffer
                dataPointsBuffer.clear();
                dotColorsBuffer.clear();
                // reset search for local minimum:
                localMinAttenuation = Integer.MAX_VALUE;
            }

            // store to temporary buffers:
            dataPointsBuffer.add(new Entry(timestampLocalTZDay0, attenuation));
            dotColorsBuffer.add(getDotColorForAttenuation(attenuation));
            // if found, store local min value
            if (localMinAttenuation > attenuation) {
                localMinAttenuation = attenuation;
            }

            // if this is the last entry, handle the situation separately
            if (i == numLastScanRecord) {
                // Now copy the block we have collected so far
                boolean minHandled = false;
                for (int pos = 0; pos < dataPointsBuffer.size(); pos++) {
                    Entry entry = dataPointsBuffer.get(pos);
                    int color = dotColorsBuffer.get(pos);
                    //Log.d(TAG, "Entry at: "+dateFormat.format(new Date(getMillisFromSeconds((int) entry.getX()))));
                    if (!minHandled && entry.getY() <= localMinAttenuation) {
                        // This is the minimum, store in the "minimum" list
                        //Log.d(TAG, "Minimum found.");
                        result.dataPointsMinAttenuation.add(entry);
                        result.dotColorsMinAttenuation.add(color);
                        minHandled = true;
                    } else {
                        // This is one of the other entries, store in the "normal" list
                        result.dataPoints.add(entry);
                        result.dotColors.add(color);
                    }
                }
            }

            // prepare break detection
            lastTimestampLocalTZDay0 = timestampLocalTZDay0;
            i++;
        }
        return result;
    }

    private static int getDotColorForAttenuation(int attenuation) {
        if (attenuation < 55) {
            return redColor;
        } else if (attenuation <= 63) {
            return orangeColor;
        } else if (attenuation <= 73) {
            return yellowColor;
        } else {
            return greenColor;
        }
    }

    private void configureDetailsChart(LineChart chartView, List<Entry> dataPointsMinAttenuation, ArrayList<Integer> dotColorsMinAttenuation,
                                       List<Entry> dataPoints, ArrayList<Integer> dotColors,
                                       int minTimestampLocalTZDay0, int maxTimestampLocalTZDay0,
                                       int minAttenuation, int maxAttenuation,
                                       Context context) {
        LineDataSet dataSetMin = new LineDataSet(dataPointsMinAttenuation, "Minimum Attenuation"); // add entries to dataSetMin
        dataSetMin.setAxisDependency(YAxis.AxisDependency.LEFT);
        dataSetMin.setCircleColors(dotColorsMinAttenuation);
        dataSetMin.setDrawCircleHole(false);
        dataSetMin.setCircleHoleColor(resolveColorAttr(android.R.attr.colorForegroundInverse, context));
        //dataSetMin.enableDashedLine(0, 1, 0);
        dataSetMin.setColor(lineColor);
        dataSetMin.setDrawValues(false);
        dataSetMin.setHighlightEnabled(false);
        dataSetMin.setCircleRadius(5.0f);
        dataSetMin.setCircleHoleRadius(2.5f);

        LineDataSet dataSetRest = new LineDataSet(dataPoints, "Attenuation"); // add entries to dataSetRest
        dataSetRest.setAxisDependency(YAxis.AxisDependency.LEFT);
        dataSetRest.setCircleColors(dotColors);
        dataSetRest.setDrawCircleHole(false);
        dataSetRest.setCircleHoleColor(resolveColorAttr(android.R.attr.colorForegroundInverse, context));
        dataSetRest.enableDashedLine(0, 1, 0);  // these parameters mean: do not show line
        dataSetRest.setDrawValues(false);
        dataSetRest.setHighlightEnabled(false);
        dataSetRest.setCircleRadius(3.0f);
        dataSetRest.setCircleHoleRadius(1.5f);

        LineData lineData = new LineData(dataSetMin);
        lineData.addDataSet(dataSetRest);
        chartView.setData(lineData);

        String deviceDateFormat = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "Hm");
        DateFormat dateFormat = new SimpleDateFormat(deviceDateFormat, Locale.getDefault());
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        // UTC because we don't want DateFormat to do additional time zone compensation

        // the labels that should be drawn on the XAxis
        ValueFormatter xAxisFormatter = new ValueFormatter() {
            @Override
            public String getAxisLabel(float value, AxisBase axis) {
                return dateFormat.format(new Date(getMillisFromSeconds((int) value)));
            }
        };
        // the labels that should be drawn on the YAxis
        ValueFormatter yAxisFormatter = new ValueFormatter() {
            @SuppressLint("DefaultLocale")
            @Override
            public String getAxisLabel(float value, AxisBase axis) {
                return String.format("%2d", (int) value);
            }
        };

        XAxis xAxis = chartView.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setValueFormatter(xAxisFormatter);
        xAxis.setGranularity(60.0f); // minimum axis-step (interval) is 60 seconds
        xAxis.setGranularityEnabled(true);
        xAxis.setDrawGridLines(false);
        xAxis.setAxisMinimum(minTimestampLocalTZDay0-60);
        xAxis.setAxisMaximum(maxTimestampLocalTZDay0+60);
        xAxis.setTextSize(11.0f*this.textScalingFactor);
        xAxis.setTextColor(resolveColorAttr(android.R.attr.textColorPrimary, context));
        chartView.setExtraBottomOffset(3.0f);

        float axisMinimum;
        if (minAttenuation < 0) {
            axisMinimum = (float) minAttenuation;  // should not happen in real life, only during a relay attack?
        } else {
            axisMinimum = 0.0f;
        }
        float axisMaximum;
        if (maxAttenuation > 0) {
            axisMaximum = (float) maxAttenuation;
        } else {
            axisMaximum = 0.0f;
        }
        YAxis yAxis = chartView.getAxisLeft();
        yAxis.setGranularity(1.0f); // minimum axis-step (interval) is 1
        yAxis.setGranularityEnabled(true);
        yAxis.setAxisMinimum(axisMinimum);
        yAxis.setAxisMaximum(axisMaximum);
        yAxis.setGridColor(ContextCompat.getColor(context, R.color.colorGridLines));
        yAxis.setGridLineWidth(1.0f);
        yAxis.setDrawGridLines(true);
        yAxis.setValueFormatter(yAxisFormatter);
        yAxis.setInverted(true);
        yAxis.setTextColor(resolveColorAttr(android.R.attr.textColorPrimary, context));

        chartView.getAxisRight().setAxisMinimum(axisMinimum);
        chartView.getAxisRight().setAxisMaximum(axisMaximum);
        chartView.getAxisRight().setDrawLabels(false);
        chartView.getAxisRight().setDrawGridLines(false);
        chartView.getLegend().setEnabled(false);
        chartView.getDescription().setEnabled(false);
        chartView.setScaleYEnabled(false);
        int span = maxTimestampLocalTZDay0-minTimestampLocalTZDay0;
        float maximumScaleX = span / 700.0f;
        if (maximumScaleX < 1.0f) {
            maximumScaleX = 1.0f;
        }
        //Log.d(TAG, "maximumScaleX: "+maximumScaleX);
        chartView.getViewPortHandler().setMaximumScaleX(maximumScaleX);
        chartView.invalidate(); // refresh
    }

    private String getReportTypeStr(DiagnosisKeysProtos.TemporaryExposureKey.ReportType reportType) {
        switch (reportType) {
            case REVOKED:
                return(mContext.getString(R.string.report_type_revoked));
            case UNKNOWN:
                return(mContext.getString(R.string.report_type_unknown));
            case RECURSIVE:
                return(mContext.getString(R.string.report_type_recursive));
            case SELF_REPORT:
                return(mContext.getString(R.string.report_type_self_report));
            case CONFIRMED_TEST:
                return(mContext.getString(R.string.report_type_confirmed_test));
            case CONFIRMED_CLINICAL_DIAGNOSIS:
                return(mContext.getString(R.string.report_type_clinical_diagnosis));
            default:
                return mContext.getString(R.string.invalid);
        }
    }

    @Override
    public int getItemCount() {
        return mValues.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public final TextView mTextViewMainText;
        public final TextView mTextViewRiskDetails;
        public final TextView mTextViewTxPower;
        public final LineChart mChartView;
        public final MapView mMapView;
        public DkAndMatchEntries mDkAndMatchEntries;

        @SuppressLint("ClickableViewAccessibility")
        public ViewHolder(View view, boolean showMap, Context context) {
            super(view);
            mTextViewMainText = view.findViewById(R.id.textViewMainText);
            mTextViewRiskDetails = view.findViewById(R.id.textViewRiskDetails);
            mTextViewTxPower = view.findViewById(R.id.textViewTxPower);
            mChartView = view.findViewById(R.id.chart);

            mMapView = view.findViewById(R.id.map);
            if (showMap) {
                mMapView.getOverlays().add(new CopyrightOverlay(context));
                mMapView.setTileSource(TileSourceFactory.MAPNIK);
                mMapView.getZoomController().setVisibility(Visibility.ALWAYS);
                mMapView.setMultiTouchControls(true);
                mMapView.setOnTouchListener(new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        // Don't allow scrolling in map for simplicity and consistency.
                        // To support vertical scrolling, we would have to create
                        // a custom MapView that overrides RecyclerView scrolling.
                        return true;
                    }
                });
            }
            else {
                mMapView.setVisibility(View.GONE);
            }
        }

        @Override
        @NonNull
        public String toString() {
            return super.toString() + " '" + mTextViewMainText.getText() + "'";
        }
    }

    public void toggleShowAllScans() {
        this.showAllScans = !this.showAllScans;
        this.notifyDataSetChanged();
    }

    public void toggleShowMap() {
        this.mapDisplayAllowed = !this.mapDisplayAllowed;
        this.notifyDataSetChanged();
    }
}
