//
//   Copyright 2018-2025  SenX S.A.S.
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

package io.warp10.script.functions;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;

import io.warp10.script.NamedWarpScriptFunction;
import io.warp10.script.WarpScriptStackFunction;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptStack;

/**
 * Converts nested lists of numbers into a Matrix
 */
public class TOMAT extends NamedWarpScriptFunction implements WarpScriptStackFunction {

  public TOMAT(String name) {
    super(name);
  }

  @Override
  public Object apply(WarpScriptStack stack) throws WarpScriptException {

    Object o = stack.pop();

    if (o instanceof RealVector) {

      RealMatrix matrix = MatrixUtils.createRealMatrix(((RealVector) o).getDimension(), 1);

      matrix.setColumnVector(0, (RealVector) o);

      stack.push (matrix);

      return stack;
    }

    int rows;
    int cols;

    if (o instanceof Long) {
      rows = ((Long) o).intValue();
      o = stack.pop();
      if (!(o instanceof byte[])) {
        throw new WarpScriptException(getName() + " expected a serialized matrix as BYTES.");
      }
      byte[] data = (byte[]) o;

      if (0 != data.length % Double.BYTES || 0 != (data.length / Double.BYTES) % rows) {
        throw new WarpScriptException(getName() + " invalid BYTES length.");
      }

      cols = data.length / Double.BYTES / rows;

      RealMatrix matrix = MatrixUtils.createRealMatrix(rows, cols);

      ByteBuffer bb = ByteBuffer.wrap(data);
      bb.order(ByteOrder.BIG_ENDIAN);

      for (int row = 0; row < rows; row++) {
        for (int col = 0; col < cols; col++) {
          matrix.setEntry(row, col, Double.longBitsToDouble(bb.getLong()));
        }
      }

      stack.push(matrix);
      return stack;
    }

    if (!(o instanceof List)) {
      throw new WarpScriptException(getName() + " expects a 2D array onto the stack.");
    }

    rows = ((List) o).size();
    cols = -1;

    for (Object oo: (List) o) {
      if (!(oo instanceof List)) {
        throw new WarpScriptException(getName() + " expects a 2D array onto the stack.");
      }
      if (-1 == cols) {
        cols = ((List) oo).size();
      } else if (cols != ((List) oo).size()) {
        throw new WarpScriptException(getName() + " expects a common number of columns throughout the 2D array.");
      }
    }

    double[][] doubles = new double[rows][cols];

    for (int i = 0; i < rows; i++) {
      List<Object> row = (List<Object>) ((List) o).get(i);
      for (int j = 0; j < cols; j++) {
        Object elt = row.get(j);
        if (!(elt instanceof Number)) {
          throw new WarpScriptException(getName() + " expects a numeric 2D array onto the stack.");
        }
        doubles[i][j] = ((Number) elt).doubleValue();
      }
    }

    RealMatrix mat = MatrixUtils.createRealMatrix(doubles);

    stack.push(mat);

    return stack;
  }
}
