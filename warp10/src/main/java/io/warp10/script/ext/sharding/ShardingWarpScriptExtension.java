package io.warp10.script.ext.sharding;

import java.util.HashMap;
import java.util.Map;

import io.warp10.script.WarpScriptLib;
import io.warp10.warp.sdk.WarpScriptExtension;

public class ShardingWarpScriptExtension extends WarpScriptExtension {
  
  private static final Map<String,Object> functions;
  
  static final String SHARDING_POOLSIZE = "sharding.poolsize";
  static final String SHARDING_ENDPOINTS = "sharding.endpoints";
  static final String SHARDING_MAXTHREADSPERREQUEST = "sharding.maxthreadsperrequest";
  
  static {
    functions = new HashMap<String, Object>();
    
    functions.put(WarpScriptLib.FETCH, new RCMD(WarpScriptLib.FETCH));
    functions.put(WarpScriptLib.FETCHLONG, new RCMD(WarpScriptLib.FETCH));
    functions.put(WarpScriptLib.FETCHDOUBLE, new RCMD(WarpScriptLib.FETCH));
    functions.put(WarpScriptLib.FETCHBOOLEAN, new RCMD(WarpScriptLib.FETCH));
    functions.put(WarpScriptLib.FETCHSTRING, new RCMD(WarpScriptLib.FETCH));
    functions.put(WarpScriptLib.FIND, new RCMD(WarpScriptLib.FIND));
  }
  
  @Override
  public Map<String, Object> getFunctions() {
    return functions;
  }
}
