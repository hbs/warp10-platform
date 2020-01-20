//
//   Copyright 2020  SenX S.A.S.
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.
//

package io.warp10.script.aggregator;

import com.geoxp.GeoXPLib;
import io.warp10.continuum.gts.GeoTimeSerie;
import io.warp10.script.NamedWarpScriptFunction;
import io.warp10.script.WarpScriptAggregatorFunction;
import io.warp10.script.WarpScriptBucketizerFunction;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptMapperFunction;
import io.warp10.script.WarpScriptReducerFunction;
import io.warp10.script.binary.EQ;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Return the median of the values on the interval.
 * If median data point has an associated location and elevation, return it
 * If forbidNulls and null among inputs, the function will raise an exception.
 */
public class Median extends NamedWarpScriptFunction implements WarpScriptAggregatorFunction, WarpScriptMapperFunction, WarpScriptBucketizerFunction, WarpScriptReducerFunction {

  private final boolean forbidNulls;

  public Median(String name, boolean forbidNulls) {
    super(name);
    this.forbidNulls = forbidNulls;
  }

  @Override
  public Object apply(Object[] args) throws WarpScriptException {
    long tick = (long) args[0];
    long[] locations = (long[]) args[4];
    long[] elevations = (long[]) args[5];
    final Object[] values = (Object[]) args[6];

    //
    // count null value. Also check if there is one double at least
    //
    int nullCounter = 0;
    boolean inputHasDouble = false;
    for (Object v: values) {
      if (null == v) {
        nullCounter++;
      }
      inputHasDouble |= (v instanceof Double);
    }

    if (nullCounter != 0 && this.forbidNulls) {
      throw new WarpScriptException(this.getName() + " cannot compute median of null values.");
    }

    Integer[] indices = new Integer[values.length];
    for (int i = 0; i < indices.length; i++) {
      indices[i] = i;
    }
    //
    // sort indices from values, null at the end of the sorted array.
    //
    Arrays.sort(indices, new Comparator<Integer>() {
      @Override
      public int compare(Integer idx1, Integer idx2) {
        if (null == values[idx1] && null == values[idx2]) {
          return 0;
        } else if (null == values[idx1] || null == values[idx2]) {
          return null == values[idx1] ? 1 : -1;
        } else if (values[idx1] instanceof Number && values[idx2] instanceof Number) {
          return EQ.compare((Number) values[idx1], (Number) values[idx2]);
        } else {
          throw new RuntimeException("MEDIAN can only operate on numeric Geo Time Series.");
        }
      }
    });

    long location = 0;
    long elevation = GeoTimeSerie.NO_ELEVATION;
    Object median;

    int nonNullLength = values.length - nullCounter;

    //
    // singleton case
    //
    if (1 == nonNullLength) {
      return new Object[]{
          tick, locations[indices[0]], elevations[indices[0]], values[indices[0]]
      };
    } else {
      if (0 == nonNullLength % 2) {
        //
        // even number of non null values, return mean of both values.
        // If there is a location for both points, return centroid of locations
        // If there is an elevation for both points, return mean of elevations
        //
        int low = indices[nonNullLength / 2 - 1];
        int high = indices[nonNullLength / 2];
        median = (((Number) values[low]).doubleValue() + ((Number) values[high]).doubleValue()) / 2.0D;
        if (GeoTimeSerie.NO_ELEVATION != elevations[low] && GeoTimeSerie.NO_ELEVATION != elevations[high]) {
          elevation = (elevations[low] + elevations[high]) / 2;
        }
        if (GeoTimeSerie.NO_LOCATION != locations[low] && GeoTimeSerie.NO_LOCATION != locations[high]) {
          long[] xyLow = GeoXPLib.xyFromGeoXPPoint(locations[low]);
          long[] xyHigh = GeoXPLib.xyFromGeoXPPoint(locations[high]);
          location = GeoXPLib.toGeoXPPoint((xyLow[0] + xyHigh[0]) / 2, (xyLow[1] + xyHigh[1]) / 2);
        }

      } else {
        //
        // odd number of non null values
        //
        location = locations[indices[nonNullLength / 2]];
        elevation = elevations[indices[nonNullLength / 2]];
        median = ((Number) values[indices[nonNullLength / 2]]).doubleValue();
      }

    }

    // if the input has only long values, return a long.
    if (!inputHasDouble) {
      median = ((Double) median).longValue();
    }
    return new Object[]{tick, location, elevation, median};
  }
}
