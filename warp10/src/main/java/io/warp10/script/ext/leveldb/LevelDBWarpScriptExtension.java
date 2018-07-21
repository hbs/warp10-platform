package io.warp10.script.ext.leveldb;

import java.util.HashMap;
import java.util.Map;

import io.warp10.warp.sdk.WarpScriptExtension;

public class LevelDBWarpScriptExtension extends WarpScriptExtension {

  private static final Map<String,Object> functions;
  
  public static final String LEVELDB_SECRET = "leveldb.secret";
  
  static {
    functions = new HashMap<String, Object>();
  
    functions.put("SSTFIND", new SSTFIND("SSTFIND"));
    functions.put("SSTPURGE", new SSTPURGE("SSTPURGE"));
  }
  
  @Override
  public Map<String, Object> getFunctions() {
    return functions;
  }
}
