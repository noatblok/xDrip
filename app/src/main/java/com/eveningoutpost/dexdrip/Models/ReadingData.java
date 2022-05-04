package com.eveningoutpost.dexdrip.Models;

// class from LibreAlarm

import com.eveningoutpost.dexdrip.Models.UserError.Log;
import com.eveningoutpost.dexdrip.Models.Forecast.PolyTrendLine;
import com.eveningoutpost.dexdrip.Models.Forecast.TrendLine;

import com.eveningoutpost.dexdrip.utils.LibreTrendPoint;
import com.eveningoutpost.dexdrip.UtilityModels.Pref;

import com.eveningoutpost.dexdrip.utils.math.AdaptiveSavitzkyGolay;
import com.eveningoutpost.dexdrip.utils.math.PolynomialFitErrorEstimator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

public class ReadingData {

    private static final String TAG = "ReadingData";
    public List<GlucoseData> trend; // Per minute data.
    public List<GlucoseData> history;  // Per 15 minutes data.
    public byte[] raw_data;

    private static final byte ERROR_INFLUENCE = 1; //  The influence of each error
    private static final byte PREFERRED_AVERAGE = 5; //  Try to use 5 numbers for the average
    private static final byte MAX_DISTANCE_FOR_SMOOTHING = 7; //  If points have been removed, use up to 7 numbers for the average. not applicable for ASG smoothing

    private static final byte NOISE_HORIZON = 16;
    private static final byte CALIBRATION_NOISE_POINTS_FOR_SLOPE = 10;
    private static final double LIBRE_RAW_BG_DIVIDER = 8.5;

    public ReadingData() {
        this.trend = new ArrayList<GlucoseData>();
        this.history = new ArrayList<GlucoseData>();
        // The two bytes are needed here since some components don't like a null pointer.
        this.raw_data = new byte[2];
    }

    public ReadingData(List<GlucoseData> trend, List<GlucoseData> history) {
        this.trend = trend;
        this.history = history;
    }

    public String toString() {
        String ret = "ternd ";
        for (GlucoseData gd : trend) {
            ret += gd.toString();
            ret += " ";
        }
        ret += "history ";
        for (GlucoseData gd : history) {
            ret += gd.toString();
            ret += " ";
        }
        return "{" + ret + "}";
    }

    public static class TransferObject {
        public ReadingData data;

        public TransferObject() {
        }

        public TransferObject(long id, ReadingData data) {
            this.data = data;
        }
    }

    // A function to calculate the smoothing based only on 3 points.
    private void CalculateSmothedData3Points() {
        for (int i = 0; i < trend.size() - 2; i++) {
            trend.get(i).glucoseLevelRawSmoothed =
                    (trend.get(i).glucoseLevelRaw + trend.get(i + 1).glucoseLevelRaw + trend.get(i + 2).glucoseLevelRaw) / 3;
        }
        // Set the last two points. (doing our best - this will only be used if there are no previous readings).
        if (trend.size() >= 2) {
            // We have two points, use their average for both
            int average = (trend.get(trend.size() - 2).glucoseLevelRaw + trend.get(trend.size() - 1).glucoseLevelRaw) / 2;
            trend.get(trend.size() - 2).glucoseLevelRawSmoothed = average;
            trend.get(trend.size() - 1).glucoseLevelRawSmoothed = average;
        } else if (trend.size() == 1) {
            // Only one point, use it
            trend.get(trend.size() - 1).glucoseLevelRawSmoothed = trend.get(trend.size() - 1).glucoseLevelRaw;
        }

    }

    private void CalculateSmothedData5Points() {
        // In all places in the code, there should be exactly 16 points.
        // Since that might change, and I'm doing an average of 5, then in the case of less then 5 points,
        // I'll only copy the data as is (to make sure there are reasonable values when the function returns).
        if (trend.size() < 5) {
            for (int i = 0; i < trend.size() - 4; i++) {
                trend.get(i).glucoseLevelRawSmoothed = trend.get(i).glucoseLevelRaw;
            }
            return;
        }

        for (int i = 0; i < trend.size() - 4; i++) {
            trend.get(i).glucoseLevelRawSmoothed =
                    (trend.get(i).glucoseLevelRaw +
                            trend.get(i + 1).glucoseLevelRaw +
                            trend.get(i + 2).glucoseLevelRaw +
                            trend.get(i + 3).glucoseLevelRaw +
                            trend.get(i + 4).glucoseLevelRaw) / 5;
        }
        // We now have to calculate the last 4 points, will do our best...
        trend.get(trend.size() - 4).glucoseLevelRawSmoothed =
                (trend.get(trend.size() - 4).glucoseLevelRaw +
                        trend.get(trend.size() - 3).glucoseLevelRaw +
                        trend.get(trend.size() - 2).glucoseLevelRaw +
                        trend.get(trend.size() - 1).glucoseLevelRaw) / 4;

        trend.get(trend.size() - 3).glucoseLevelRawSmoothed =
                (trend.get(trend.size() - 3).glucoseLevelRaw +
                        trend.get(trend.size() - 2).glucoseLevelRaw +
                        trend.get(trend.size() - 1).glucoseLevelRaw) / 3;

        // Use the last two points for both last points
        trend.get(trend.size() - 2).glucoseLevelRawSmoothed =
                (trend.get(trend.size() - 2).glucoseLevelRaw +
                        trend.get(trend.size() - 1).glucoseLevelRaw) / 2;

        trend.get(trend.size() - 1).glucoseLevelRawSmoothed = trend.get(trend.size() - 2).glucoseLevelRawSmoothed;
    }

    public void CalculateSmothedData() {
        CalculateSmothedData5Points();
        // print the values, remove before release
        for (int i = 0; i < trend.size(); i++) {
            Log.e("xxx", "" + i + " raw val " + trend.get(i).glucoseLevelRaw + " smoothed " + trend.get(i).glucoseLevelRawSmoothed);
        }
    }
    // TODO JB: Rename this to make clear it also calculates the noise
    public void ClearErrors(List<LibreTrendPoint> libreTrendPoints) {
        // For the history data where each reading holds data for 15 minutes we remove only bad points.
        Iterator<GlucoseData> it = history.iterator();
        while (it.hasNext()) {
            GlucoseData glucoseData = it.next();
            try {
                if (libreTrendPoints.get(glucoseData.sensorTime).isError()) {
                    it.remove();
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception attempting to check/clear error point: " + e + " @ " + glucoseData.sensorTime);
            }
        }

        // For the per minute data, we are also going to check that the data from the last 4 minutes did not have an error.
        HashSet<Integer> errorHash = calculateErrorSet(libreTrendPoints, trend);

        it = trend.iterator();
        while (it.hasNext()) {
            GlucoseData glucoseData = it.next();

            if (errorHash.contains(glucoseData.sensorTime) || libreTrendPoints.get(glucoseData.sensorTime).rawSensorValue == 0) {
                Log.e(TAG, "Removing point glucoseData =  " + glucoseData.toString());
                it.remove();
            }
            else
            {
                // TODO JB: Not sure if this is the right place for it but it seems so
                calculateNoisePerPoint(glucoseData, libreTrendPoints, errorHash);
            }
        }
    }

    static private void calculateNoisePerPoint(GlucoseData glucoseData, List<LibreTrendPoint> libreTrendPoints, HashSet<Integer> errorHash) {
        // This function calculates the noise per point given the trendpoint data,
        // For now only calibration noise is detected by getting a trendline for the delta of the two trends and get the slope of the trendline. 
        // when the OOP2 algorithm is not correcting itself the slope should be 0 (ideally). This should prevent downstream apps like androidAPS on acting the spiky data.

        int horizon = Pref.getStringToInt("Libre2_noiseHorizon",NOISE_HORIZON);
        boolean bgInUse = !Pref.getString("calibrate_external_libre_2_algorithm_type", "calibrate_raw").equals("calibrate_raw");
        double calibrationNoise = 0;
        double noise = 0;

        final List<Double> times = new ArrayList<Double>();
        final List<Double> deltas = new ArrayList<Double>();
        final List<Double> bgs = new ArrayList<Double>();

        for (int i = 0; i < horizon + 2 && times.size() < horizon; i++) {  
            LibreTrendPoint libreTrendPoint = libreTrendPoints.get(glucoseData.sensorTime -i);
            if (errorHash.contains(glucoseData.sensorTime-i) || libreTrendPoint.rawSensorValue == 0 || libreTrendPoint.glucoseLevel <= 0) {
                continue;
            }
            if (bgInUse && i < CALIBRATION_NOISE_POINTS_FOR_SLOPE + 2 && deltas.size() < CALIBRATION_NOISE_POINTS_FOR_SLOPE) {
                deltas.add((double)libreTrendPoint.glucoseLevel - (double)(libreTrendPoint.rawSensorValue / LIBRE_RAW_BG_DIVIDER));
            }
            if (bgInUse) {
                bgs.add((double)libreTrendPoint.glucoseLevel);
            } else {
                bgs.add((double)libreTrendPoint.rawSensorValue / LIBRE_RAW_BG_DIVIDER);
            }
            times.add((double)(glucoseData.sensorTime-i)); 
        }
        
        if (bgInUse && deltas.size() >= CALIBRATION_NOISE_POINTS_FOR_SLOPE) {      
            final TrendLine time_to_delta = new PolyTrendLine(1);
            time_to_delta.setValues(PolyTrendLine.toPrimitiveFromList(deltas), PolyTrendLine.toPrimitiveFromList(times.subList(0, deltas.size())) );
            final double slope = time_to_delta.predict(1) - time_to_delta.predict(0);
            final double errorVarience = time_to_delta.errorVarience();

            // Error variance is defined by dot(r,r) / (size(r) - order - 1), to convert our slope to something which resembles errorVarience:
            final double noiseFromSlope = Math.pow(Math.abs(slope), 2) * Pref.getStringToDouble("libre_calibration_noise_correction_factor", 1); 

            // If the errorVarience is higher then the noise calculated from the slope, we use that, as in that case the data is noisy and our slope estimation may be off
            calibrationNoise = (errorVarience > noiseFromSlope) ? errorVarience : noiseFromSlope;

            Log.d(TAG, "Setting calibration noise level based on, Time: " + glucoseData.sensorTime + " Slope: " + JoH.qs(slope) + " Error Variance: " + JoH.qs(errorVarience) + " Noise: " + JoH.qs(noiseFromSlope) + " Reported: " + JoH.qs(calibrationNoise));
        }
        
        if(bgs.size() >= horizon) {
            final TrendLine time_to_bg = new PolyTrendLine(2);
            time_to_bg.setValues(PolyTrendLine.toPrimitiveFromList(bgs), PolyTrendLine.toPrimitiveFromList(times));
            noise = time_to_bg.errorVarience() * Pref.getStringToDouble("libre_noise_sensitivity", 1);
        }

        glucoseData.noise = (calibrationNoise > noise) ? calibrationNoise : noise;
        Log.i(TAG, "Setting noise, Time: " + glucoseData.sensorTime + " noise: " + JoH.qs(noise) + " calibration noise: " + JoH.qs(calibrationNoise) + " Reported: " + JoH.qs(glucoseData.noise));
    }

    // A helper function to calculate the errors and their influence on data.
    static private HashSet<Integer> calculateErrorSet(List<LibreTrendPoint> libreTrendPoints, List<GlucoseData> trend) {
        // Create a set of all the points with errors. (without changing libreTrendPoints). Each point with an error
        // has an influence to the next 4 points.
        HashSet<Integer> errorHash = new HashSet<Integer>();
        Iterator<GlucoseData> it = trend.iterator();

        // Find the minimum values to look for error
        int min = libreTrendPoints.size();
        int max = 0;
        while (it.hasNext()) {
            GlucoseData glucoseData = it.next();
            min = Math.min(min, glucoseData.sensorTime);
            max = Math.max(max, glucoseData.sensorTime);
        }
        min = Math.max(0, min - MAX_DISTANCE_FOR_SMOOTHING);
        max = Math.min(libreTrendPoints.size(), max + MAX_DISTANCE_FOR_SMOOTHING);

        for (int i = min; i < max; i++) {
            if (libreTrendPoints.get(i).isError()) {
                for (int j = 0; j < ERROR_INFLUENCE; j++) {
                    errorHash.add(i + j);
                }
            }
        }
        return errorHash;

    }

    public void calculateSmoothDataImproved(List<LibreTrendPoint> libreTrendPoints, boolean BgValSmoothing) {
        // use the data on libreTrendPoints to do the calculations.
        // Try to use the first 5 points to do the average if they exist and if not use up to 7 more points.

        HashSet<Integer> errorHash = calculateErrorSet(libreTrendPoints, trend);
        Iterator<GlucoseData> it = trend.iterator();
        while (it.hasNext()) {
            GlucoseData glucoseData = it.next();
            boolean remove = true;
            if (Pref.getBooleanDefaultFalse("Libre2_useSavitzkyGolay")) {
                remove = calculateSmoothDataPerPointSavitzkyGolay(glucoseData, libreTrendPoints, BgValSmoothing, errorHash);
            } else {
                remove = calculateSmoothDataPerPoint(glucoseData, libreTrendPoints, BgValSmoothing, errorHash);
            }            
            if (remove) {
                it.remove();
            }
        }
    }

    // true means we need to remove this objects.
    private boolean calculateSmoothDataPerPoint(GlucoseData glucoseData, List<LibreTrendPoint> libreTrendPoints, boolean BgValSmoothing, HashSet<Integer> errorHash) {
        if (glucoseData.sensorTime < MAX_DISTANCE_FOR_SMOOTHING) {
            // First values are not interesting, but would make the algorithm more complex.
            return false;
        }

        int points_used = 0;
        double sum = 0;
        double sumBg = 0;
        int points_used_bg = 0;

        for (int i = 0; i < MAX_DISTANCE_FOR_SMOOTHING && points_used < PREFERRED_AVERAGE; i++) {
            LibreTrendPoint libreTrendPoint = libreTrendPoints.get(glucoseData.sensorTime - i);
            if (errorHash.contains(glucoseData.sensorTime - i) || libreTrendPoint.rawSensorValue == 0) {
                if (libreTrendPoint.getSensorTime() != 0) {
                    Log.d(TAG, "Not using point because it is in error" + libreTrendPoint);
                }
                continue;
            }
            sum += libreTrendPoint.rawSensorValue;
            points_used++;
            Log.d(TAG, "Using  point for some " + libreTrendPoint);

            if (BgValSmoothing && libreTrendPoint.glucoseLevel > 0) {
                sumBg += libreTrendPoint.glucoseLevel;
                points_used_bg++;
            }
        }
        if (points_used > 0 &&
                (BgValSmoothing == false || points_used_bg > 0)) {
            glucoseData.glucoseLevelRawSmoothed = (int) (sum / points_used);
            glucoseData.glucoseLevelSmoothed = (int) (sumBg / points_used_bg) * 1000;
            Log.d(TAG, "setting smooth data based on " + points_used + " points " + glucoseData);
        } else {
            //glucoseData.glucoseLevelRawSmoothed = 0;
            Log.e(TAG, "Removing object because it does not have any data " + glucoseData);
            return true;
        }
        return false;
    }

    // true means we need to remove this objects.
    private boolean calculateSmoothDataPerPointSavitzkyGolay(GlucoseData glucoseData, List<LibreTrendPoint> libreTrendPoints, boolean BgValSmoothing, HashSet<Integer> errorHash) {
        int horizon = Pref.getStringToInt("Libre2_sgHorizon", 25);
        int lag = Pref.getStringToInt("Libre2_sgLag", 2);
        int polynomialOrder = Pref.getStringToInt("Libre2_sgPolynomialOrder", 3);
        double weightedAverageFraction = Pref.getStringToDouble("Libre2_sgWeightedAverageFraction", 0.333);
        int weightedAverageHorizon = Pref.getStringToInt("Libre2_sgWeightedAverageHorizon", 15);

        AdaptiveSavitzkyGolay asgBg = new AdaptiveSavitzkyGolay(lag, polynomialOrder, weightedAverageFraction, weightedAverageHorizon, 1);
        AdaptiveSavitzkyGolay asgRaw = new AdaptiveSavitzkyGolay(lag, polynomialOrder, weightedAverageFraction, weightedAverageHorizon, 1);

        for (int i = horizon; i >= 0; i--) {   
            LibreTrendPoint libreTrendPoint = libreTrendPoints.get(glucoseData.sensorTime - i);
            if (errorHash.contains(glucoseData.sensorTime - i)) {
                Log.d(TAG, "Not using point because it is in error" + libreTrendPoint);
                continue;
            }
            if (libreTrendPoint.rawSensorValue > 0) {
                try {
                    asgRaw.addMeasurement(glucoseData.sensorTime - i, (double)libreTrendPoint.rawSensorValue);
                    // Log this to verbose we can use a lot of points we dont want to clog the debug log
                    Log.v(TAG, "Using  point for some " + libreTrendPoint); 
                } catch (RuntimeException e) {
                    Log.e(TAG, "Failed to add measurement: ",e);
                }
                
            }
            if (BgValSmoothing && libreTrendPoint.glucoseLevel > 0) {  
                try {
                    asgBg.addMeasurement(glucoseData.sensorTime - i, (double)libreTrendPoint.glucoseLevel);
                    // Log this to verbose we can use a lot of points we dont want to clog the debug log
                    Log.v(TAG, "Using  point for some " + libreTrendPoint); 
                } catch (RuntimeException e) {
                    Log.e(TAG, "Failed to add measurement: ",e);
                }
            }
        }
        try {
            glucoseData.glucoseLevelRawSmoothed = (int)Math.round(asgRaw.estimateValue());
            if (BgValSmoothing)
            {
                glucoseData.glucoseLevelSmoothed = (int)Math.round(asgBg.estimateValue() * 1000);
                Log.i(TAG, "ASG setting smooth data based on " + asgRaw.getMeasurementCount() + " points for the raw values and " + asgBg.getMeasurementCount() + " points for the OOP native values \n" + glucoseData);
            }
            else
            {
                Log.i(TAG, "ASG setting smooth data based on " + asgRaw.getMeasurementCount() + " points " + glucoseData);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to obtain smoothed BG value, falling back to weighted average",e);
            return calculateSmoothDataPerPoint(glucoseData, libreTrendPoints, BgValSmoothing, errorHash);
        }
        return false;
    }

    public void copyBgVals(List<LibreTrendPoint> libreTrendPoints) {
        //
        // List<GlucoseData> trend
        // }
        for (GlucoseData glucoseData : trend) {
            if (glucoseData.sensorTime < 0 || glucoseData.sensorTime >= libreTrendPoints.size()) {
                Log.e(TAG, "invalid sensorTime " + glucoseData);
                continue;
            }
            libreTrendPoints.get(glucoseData.sensorTime).glucoseLevel = glucoseData.glucoseLevel;
        }
    }
}
