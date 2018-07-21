package io.warp10.script.ext.leveldb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.hadoop.hbase.util.Bytes;
import org.bouncycastle.util.encoders.Hex;

import com.google.common.primitives.Longs;

import io.warp10.WarpConfig;
import io.warp10.continuum.gts.GeoTimeSerie;
import io.warp10.continuum.gts.MetadataIdComparator;
import io.warp10.continuum.store.Constants;
import io.warp10.continuum.store.Directory;
import io.warp10.continuum.store.Store;
import io.warp10.continuum.store.thrift.data.Metadata;
import io.warp10.script.NamedWarpScriptFunction;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptStack;
import io.warp10.script.WarpScriptStackFunction;
import io.warp10.standalone.Warp;

/**
 * Remove a specified set of SST files by closing LevelDB, removing the files, calling repair and reopening LevelDB.
 */
public class SSTPURGE extends NamedWarpScriptFunction implements WarpScriptStackFunction {
  
  private static final String LEVELDB_SECRET;
  
  static {
    Properties props = WarpConfig.getProperties();
    
    LEVELDB_SECRET = props.getProperty(LevelDBWarpScriptExtension.LEVELDB_SECRET);
  }
  
  public SSTPURGE(String name) {
    super(name);
  }
  
  @Override
  public Object apply(WarpScriptStack stack) throws WarpScriptException {
    Object top = stack.pop();
    String secret = top.toString();

    top = stack.pop();
    
    if (!secret.equals(LEVELDB_SECRET)) {
      throw new WarpScriptException(getName() + " invalid LevelDB secret.");
    }
    
    if (!(top instanceof List)) {
      throw new WarpScriptException(getName() + " expects a list of SST table ids below the LevelDB secret.");
    }
      
    try {
      for (Object elt: (List) top) {
        if (!(elt instanceof Long)) {
          throw new WarpScriptException(getName() + " expects a list of SST table ids below the LevelDB secret.");
        }
      }
      Warp.getDB().purge((List<Long>) top);
    } catch (IOException ioe) {
      throw new WarpScriptException(ioe);
    }
    
    return stack;
  }
  
  /*
   * Determine sst files with a single GTS
'maxlevel' STORE
<%
  DROP
  LIST-> DROP
  'largest' STORE
  'smallest' STORE
  'file' STORE
  $maxlevel !=
  <% NULL %>
  <%
    $smallest $largest 'hello'
    <%
      SSTFIND
      DUP SIZE 1 > // If the sst file contains more than 1 GTS, discard it
      <% DROP NULL %>
      <% $file SWAP 2 ->LIST %>
      IFTE
    %>
    <% NULL %> <% %> TRY
  %> IFTE
%> LMAP
[] SWAP
<%
  DUP ISNULL <% DROP %> <% +! %> IFTE
%> FOREACH
   */
}
