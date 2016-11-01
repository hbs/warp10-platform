package io.warp10.script.ext.memcached;

import io.warp10.continuum.store.Constants;
import io.warp10.script.NamedWarpScriptFunction;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptStack;
import io.warp10.script.WarpScriptStackFunction;

import java.sql.Date;
import java.util.List;

import com.whalin.MemCached.MemCachedClient;

/**
 * Store a list of Key/Value in memcached
 */
public class MCPUT extends NamedWarpScriptFunction implements WarpScriptStackFunction {
  
  public MCPUT(String name) {
    super(name);
  }
  
  @Override
  public Object apply(WarpScriptStack stack) throws WarpScriptException {

    Object top = stack.pop();
    
    if (!(top instanceof List)) {
      throw new WarpScriptException(getName() + " expects a list of [ key, byte array value, (expiry) ] on top of the stack.");
    }
    
    MemCachedClient mc = new MemCachedClient(MemcachedWarpScriptExtension.MCPOOL_NAME);

    for (Object elt: (List) top) {
      if (!(elt instanceof List)) {
        throw new WarpScriptException(getName() + " expects a list of [ key, byte array value, (expiry) ] lists on top of the stack.");
      }
      
      List<Object> lelt = (List<Object>) elt;
      
      //if (!(lelt.get(1) instanceof byte[])) {
      //  throw new WarpScriptException(getName() + " can only store byte array values.");
      //}
      
      Date expiry = null;
      
      if (lelt.size() > 2) {
        expiry = new Date(((Number) lelt.get(2)).longValue() / Constants.TIME_UNITS_PER_MS);
      }
      
      if (null == expiry) {
        mc.set(lelt.get(0).toString(), lelt.get(1));
      } else {
        mc.set(lelt.get(0).toString(), lelt.get(1), expiry);
      }
    }
        
    return stack;
  }
  
}
