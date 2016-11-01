package io.warp10.script.ext.memcached;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.whalin.MemCached.SockIOPool;

import io.warp10.warp.sdk.WarpScriptExtension;

public class MemcachedWarpScriptExtension extends WarpScriptExtension {    

  static final String MCPOOL_NAME = UUID.randomUUID().toString();
  
  private static final Map<String, Object> functions;
  
  static {
    functions = new HashMap<String, Object>();
    
    functions.put("MCPUT", new MCPUT("MCPUT"));
    functions.put("MCGET", new MCGET("MCGET"));
    
    String[] serverlist = { "127.0.0.1:11211" };
    
    if (null != System.getProperty("memcached.serverlist")) {
      serverlist = System.getProperty("memcached.serverlist").split(",");
    }
    
    SockIOPool pool = SockIOPool.getInstance(MCPOOL_NAME);
    pool.setServers(serverlist);
    pool.setSocketConnectTO(2);
    pool.setSocketTO(2);
    pool.initialize();
  }
  
  @Override
  public Map<String, Object> getFunctions() {
    return functions;
  }
}
