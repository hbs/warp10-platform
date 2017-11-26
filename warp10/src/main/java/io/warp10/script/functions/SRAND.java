//
//   Copyright 2017  Cityzen Data
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

import java.util.Random;

import io.warp10.script.NamedWarpScriptFunction;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptStack;
import io.warp10.script.WarpScriptStackFunction;

/**
 * Pushes onto the stack a random number between 0 and 1 from the seeded PRNG
 */
public class SRAND extends NamedWarpScriptFunction implements WarpScriptStackFunction {
  
  public SRAND(String name) {
    super(name);
  }
  
  @Override
  public Object apply(WarpScriptStack stack) throws WarpScriptException {
    
    Random prng = (Random) stack.getAttribute(PRNG.ATTRIBUTE_SEEDED_PRNG);
    
    if (null == prng) {
      throw new WarpScriptException(getName() + " seeded PRNG was not initialized.");
    }
    
    stack.push(prng.nextDouble());
    return stack;
  }
}
