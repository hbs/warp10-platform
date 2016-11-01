package io.warp10.script.ext.memcached;

import io.warp10.script.NamedWarpScriptFunction;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptStack;
import io.warp10.script.WarpScriptStackFunction;

import java.util.List;

import com.whalin.MemCached.MemCachedClient;

/**
 * Retrieve values stored under a set of keys in memcached
 */
public class MCGET extends NamedWarpScriptFunction implements WarpScriptStackFunction {
  
  public MCGET(String name) {
    super(name);
  }
  
  @Override
  public Object apply(WarpScriptStack stack) throws WarpScriptException {

    Object top = stack.pop();
    
    if (!(top instanceof List)) {
      throw new WarpScriptException(getName() + " expects a list of keys on top of the stack.");
    }
    
    String[] keys = new String[((List) top).size()];
    
    for (int i = 0; i < keys.length; i++) {
      keys[i] = ((List) top).get(i).toString();
    }
    
    MemCachedClient mc = new MemCachedClient(MemcachedWarpScriptExtension.MCPOOL_NAME);

    stack.push(mc.getMulti(keys));
        
    return stack;
  }
  
}
