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

/**
 * Identify Geo Time Series™ which have data in a given key range, supposively from an SST file
 */
public class SSTFIND extends NamedWarpScriptFunction implements WarpScriptStackFunction {
  
  private static final String LEVELDB_SECRET;
  
  static {
    Properties props = WarpConfig.getProperties();
    
    LEVELDB_SECRET = props.getProperty(LevelDBWarpScriptExtension.LEVELDB_SECRET);
  }
  
  private String attrkey = "sstfind.cachekey." + UUID.randomUUID().toString();
  
  public SSTFIND(String name) {
    super(name);
  }
  
  @Override
  public Object apply(WarpScriptStack stack) throws WarpScriptException {
    Object top = stack.pop();
    String secret = top.toString();

    top = stack.pop();        
    byte[] largest = null == top ? null : Hex.decode(top.toString());
    
    top = stack.pop();
    byte[] smallest = null == top ? null : Hex.decode(top.toString());
    
    if (!secret.equals(LEVELDB_SECRET)) {
      throw new WarpScriptException(getName() +" invalid SSTFIND secret.");
    }
    
    if (null == smallest || null == largest) {
      stack.setAttribute(this.attrkey, null);
      return stack;
    }
    
    byte[] smallestClassId;
    byte[] smallestLabelsId;
    byte[] smallestTimestamp;
    
    boolean smallestData = false;
    
    if (0 == Bytes.indexOf(smallest, Store.HBASE_RAW_DATA_KEY_PREFIX)) {
      // 128BITS
      smallestClassId = Arrays.copyOfRange(smallest, Store.HBASE_RAW_DATA_KEY_PREFIX.length, Store.HBASE_RAW_DATA_KEY_PREFIX.length + 8);
      smallestLabelsId = Arrays.copyOfRange(smallest, Store.HBASE_RAW_DATA_KEY_PREFIX.length + 8, Store.HBASE_RAW_DATA_KEY_PREFIX.length + 16);
      smallestTimestamp = Arrays.copyOfRange(smallest, Store.HBASE_RAW_DATA_KEY_PREFIX.length + 16, Store.HBASE_RAW_DATA_KEY_PREFIX.length + 24);
      smallestData = true;
    } else if (0 == Bytes.indexOf(smallest, Directory.HBASE_METADATA_KEY_PREFIX)) {
      smallestClassId = Arrays.copyOfRange(smallest, Directory.HBASE_METADATA_KEY_PREFIX.length, Directory.HBASE_METADATA_KEY_PREFIX.length + 8);
      smallestLabelsId = Arrays.copyOfRange(smallest, Directory.HBASE_METADATA_KEY_PREFIX.length + 8, Directory.HBASE_METADATA_KEY_PREFIX.length + 16);
      smallestTimestamp = null;
      smallestData = false;
    } else {
      throw new WarpScriptException(getName() + " invalid key range.");
    }
    
    byte[] largestClassId;
    byte[] largestLabelsId;
    byte[] largestTimestamp;

    boolean largestData = false;
    
    if (0 == Bytes.indexOf(largest, Store.HBASE_RAW_DATA_KEY_PREFIX)) {
      // 128BITS
      largestClassId = Arrays.copyOfRange(largest, Store.HBASE_RAW_DATA_KEY_PREFIX.length, Store.HBASE_RAW_DATA_KEY_PREFIX.length + 8);
      largestLabelsId = Arrays.copyOfRange(largest, Store.HBASE_RAW_DATA_KEY_PREFIX.length + 8, Store.HBASE_RAW_DATA_KEY_PREFIX.length + 16);
      largestTimestamp = Arrays.copyOfRange(largest, Store.HBASE_RAW_DATA_KEY_PREFIX.length + 16, Store.HBASE_RAW_DATA_KEY_PREFIX.length + 24);
      largestData = true;
    } else if (0 == Bytes.indexOf(largest, Directory.HBASE_METADATA_KEY_PREFIX)) {
      largestClassId = Arrays.copyOfRange(largest, Directory.HBASE_METADATA_KEY_PREFIX.length, Directory.HBASE_METADATA_KEY_PREFIX.length + 8);
      largestLabelsId = Arrays.copyOfRange(largest, Directory.HBASE_METADATA_KEY_PREFIX.length + 8, Directory.HBASE_METADATA_KEY_PREFIX.length + 16);
      largestTimestamp = null;
      largestData = false;
    } else {
      throw new WarpScriptException(getName() + " invalid key range.");
    }

    if (smallestData != largestData) {
      throw new WarpScriptException(getName() + " unable to find GTS for hybrid SST files.");
    }
    
    //
    // Scan the keys
    //
    
    final List<String> clsSel = new ArrayList<String>();
    clsSel.add("~.*");

    boolean singleGTS = 0 == Bytes.compareTo(smallestClassId, largestClassId) && 0 == Bytes.compareTo(smallestLabelsId, largestLabelsId);

    List<List<Object>> result = new ArrayList<List<Object>>();
    
    try {
      List<Map<String,String>> lblsSel = new ArrayList<Map<String,String>>();
      Map<String,String> labels = new HashMap<String,String>();
      labels.put(Constants.PRODUCER_LABEL, "~.*");
      lblsSel.add(labels);
      
      List<Metadata> metas = null;
      
      if (null != stack.getAttribute(this.attrkey)) {
        metas = (List<Metadata>) stack.getAttribute(this.attrkey);
      } else {
        metas = stack.getDirectoryClient().find(clsSel, lblsSel);
        metas.sort(MetadataIdComparator.COMPARATOR);
        stack.setAttribute(this.attrkey, metas);
      }
      
      // 128BITS
      Metadata meta = new Metadata();
      meta.setClassId(Longs.fromByteArray(smallestClassId));
      meta.setLabelsId(Longs.fromByteArray(smallestLabelsId));
      
      int idx = Collections.binarySearch(metas, meta, MetadataIdComparator.COMPARATOR);
      
      if (idx < 0) {
        idx = - (idx + 1) - 1;
        if (idx < 0) {
          idx = 0;
        }
      }
      
      for(int i = idx; i < metas.size(); i++) {
        
        meta = metas.get(i);
        
        boolean isSmallest = false;
        boolean isLargest = false;
        
        byte[] classId = Longs.toByteArray(meta.getClassId());
        
        int comp = Bytes.compareTo(classId, smallestClassId);
        
        if (comp < 0) {
          continue;
        }

        if (0 == comp) {
          // Identical class id, check labels id
          byte[] labelsId = Longs.toByteArray(meta.getLabelsId());
          
          comp = Bytes.compareTo(labelsId, smallestLabelsId);
          
          if (comp < 0) {
            continue;
          }
          
          if (0 == comp) {
            isSmallest = true;
          }
        }
        
        // current metadata id is > smallest, check largest
        
        if (comp > 0) {
          comp = Bytes.compareTo(classId, largestClassId);
          
          if (comp > 0) {
            break;
          }
          
          if (0 == comp) {
            // Identical class id, check labels id
            byte[] labelsId = Longs.toByteArray(meta.getLabelsId());
            comp = Bytes.compareTo(labelsId, largestLabelsId);
            
            if (comp > 0) {
              break;
            }
            
            if (0 == comp) {
              isLargest = true;
            }
          }                    
        }
        
        
        if (smallestData) {
          long startTs = Long.MAX_VALUE;
          long endTs = Long.MIN_VALUE;

          if (isSmallest) {
            startTs = Long.MAX_VALUE - Longs.fromByteArray(smallestTimestamp);
          }
          
          if (isLargest || singleGTS && isSmallest) {
            endTs = Long.MAX_VALUE - Longs.fromByteArray(largestTimestamp);
          }

          List<Object> gts = new ArrayList<Object>();
          GeoTimeSerie g = new GeoTimeSerie();
          g.setMetadata(meta);
          gts.add(g);
          gts.add(endTs);
          gts.add(startTs);
          result.add(gts);
        } else {
          List<Object> gts = new ArrayList<Object>();
          GeoTimeSerie g = new GeoTimeSerie();
          g.setMetadata(meta);
          gts.add(g);
          gts.add(null);
          gts.add(null);
          result.add(gts);
        }
      }
      
      stack.push(result);
    } catch (IOException ioe) {
      throw new WarpScriptException("Error while iterating on GTS.", ioe);
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
