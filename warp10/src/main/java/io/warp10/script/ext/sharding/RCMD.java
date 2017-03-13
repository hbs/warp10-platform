package io.warp10.script.ext.sharding;

import io.warp10.WarpConfig;
import io.warp10.continuum.store.Constants;
import io.warp10.script.NamedWarpScriptFunction;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptLib;
import io.warp10.script.WarpScriptStack;
import io.warp10.script.WarpScriptStackFunction;
import io.warp10.script.functions.APPEND;
import io.warp10.script.functions.SNAPSHOT;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.GZIPInputStream;

import com.google.common.base.Charsets;

public class RCMD extends NamedWarpScriptFunction implements WarpScriptStackFunction {
  
  private static final ExecutorService executor;
  
  private static final int maxThreadsPerRequest;
  
  private static URL[] endpoints;
  
  static {
    Properties props = WarpConfig.getProperties();
    
    int rmcdpool = Integer.parseInt(props.getProperty(ShardingWarpScriptExtension.SHARDING_POOLSIZE, "4"));
    maxThreadsPerRequest = Integer.parseInt(props.getProperty(ShardingWarpScriptExtension.SHARDING_MAXTHREADSPERREQUEST, Integer.toString(rmcdpool)));
    
    
    BlockingQueue<Runnable> queue = new LinkedBlockingDeque<Runnable>(rmcdpool);
    
    executor = new ThreadPoolExecutor(rmcdpool, rmcdpool, 60, TimeUnit.SECONDS, queue);
    
    if (!props.containsKey(ShardingWarpScriptExtension.SHARDING_ENDPOINTS)) {
      throw new RuntimeException("Missing endpoints defined by '" + ShardingWarpScriptExtension.SHARDING_ENDPOINTS + "'.");
    }
    
    String[] urls = props.getProperty(ShardingWarpScriptExtension.SHARDING_ENDPOINTS).split(",");
    
    endpoints = new URL[urls.length];
    
    for (int i = 0; i < urls.length; i++) {
      try {
        endpoints[i] = new URL(urls[i]);
      } catch (MalformedURLException mue) {
        throw new RuntimeException(mue);
      }
    }
  }
  
  private final String cmd;
  
  public RCMD(String name) {
    this(name,name);
  }
  
  public RCMD(String name, String cmd) {
    super(name);
    this.cmd = cmd;
  }
  
  @Override
  public Object apply(WarpScriptStack stack) throws WarpScriptException {
    // Push a mark on the stack
    stack.push(new WarpScriptStack.Mark());
    stack.swap();
    new SNAPSHOT("", false, true, true).apply(stack);
    
    final String params = stack.pop().toString();
    
    final AtomicInteger pending = new AtomicInteger(0);
    
    final AtomicBoolean aborted = new AtomicBoolean(false);
    
    Future<String>[] futures = new Future[endpoints.length];
    
    for (int i = 0; i < endpoints.length;) {
      // Wait until we have less than maxThreadsPerRequest pending requests
      while(!aborted.get() && pending.get() >= this.maxThreadsPerRequest) {
        LockSupport.parkNanos(1000000);
      }
      
      if (aborted.get()) {
        break;
      }
      
      try {
        final URL endpoint = endpoints[i];
        futures[i] = executor.submit(new Callable<String>() {
          @Override
          public String call() throws Exception {
      
            if (aborted.get()) {
              throw new WarpScriptException("Execution aborted.");
            }
            
            HttpURLConnection conn = null;
            
            try {
              // Connect to the endpoint
              conn = (HttpURLConnection) endpoint.openConnection();
              conn.setChunkedStreamingMode(8192);
              conn.setRequestProperty("Accept-Encoding", "gzip");

              // Issue the command
              conn.setDoInput(true);
              conn.setDoOutput(true);
              conn.setRequestMethod("POST");
              
              OutputStream connout = conn.getOutputStream();
              OutputStream out = connout;
              
              out.write(params.getBytes(Charsets.UTF_8));
              out.write('\n');
              out.write(WarpScriptLib.EVAL.getBytes(Charsets.UTF_8));
              out.write('\n');
              out.write(cmd.getBytes(Charsets.UTF_8));
              out.write('\n');              
              out.write(WarpScriptLib.SNAPSHOT.getBytes(Charsets.UTF_8));      
              out.write('\n');
              out.write(WarpScriptLib.TOOPB64.getBytes(Charsets.UTF_8));
              out.write('\n');
              
              connout.flush();
              
              InputStream in = conn.getInputStream();

              // Retrieve result
              if ("gzip".equals(conn.getContentEncoding())) {
                in = new GZIPInputStream(in);
              }
              
              if (HttpURLConnection.HTTP_OK != conn.getResponseCode()) {
                throw new WarpScriptException(getName() + " remote execution encountered an error: " + conn.getHeaderField(Constants.getHeader(Constants.HTTP_HEADER_ERROR_MESSAGE_DEFAULT)));
              }
              
              ByteArrayOutputStream baos = new ByteArrayOutputStream();
              
              byte[] buf = new byte[1024];
              
              while(true) {
                int len = in.read(buf);
                if (len < 0) {
                  break;
                }
                baos.write(buf, 0, len);
              }

              byte[] bytes = baos.toByteArray();
              
              // Strip '[ ' ' ]'
              String result = new String(bytes, 2, bytes.length - 4, Charsets.US_ASCII);
              
              return result;
            } finally {
              if (null != conn) {
                conn.disconnect();
              }
              pending.addAndGet(-1);
            }
          }
        });
        pending.addAndGet(1);
      } catch (RejectedExecutionException ree) {
        continue;
      }
      i++;
    }
    
    //
    // Wait until all tasks have completed
    //
    while(!aborted.get() && pending.get() > 0) {
      LockSupport.parkNanos(100000000L);
    }

    APPEND append = new APPEND("");
    
    for (int i = 0; i < this.endpoints.length; i++) {
      try {
        String result = futures[i].get();
        stack.push(result);
        
        stack.exec(WarpScriptLib.OPB64TO);
        stack.push("UTF-8");
        stack.exec(WarpScriptLib.BYTESTO);
        stack.exec(WarpScriptLib.EVAL);        
      } catch (Exception e) {
        throw new WarpScriptException(e);
      }
      
      if (i > 0) {
        append.apply(stack);
      }
    }

    return stack;
  }  
}
