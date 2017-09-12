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


package io.warp10.standalone;

import io.warp10.WarpConfig;
import io.warp10.continuum.Configuration;
import io.warp10.continuum.TimeSource;
import io.warp10.continuum.Tokens;
import io.warp10.continuum.gts.GTSDecoder;
import io.warp10.continuum.gts.GTSEncoder;
import io.warp10.continuum.gts.GTSHelper;
import io.warp10.continuum.gts.GTSWrapperHelper;
import io.warp10.continuum.gts.GeoTimeSerie;
import io.warp10.continuum.ingress.DatalogForwarder;
import io.warp10.continuum.store.Constants;
import io.warp10.continuum.store.DirectoryClient;
import io.warp10.continuum.store.GTSDecoderIterator;
import io.warp10.continuum.store.StoreClient;
import io.warp10.continuum.store.thrift.data.DatalogRequest;
import io.warp10.continuum.store.thrift.data.GTSWrapper;
import io.warp10.continuum.store.thrift.data.Metadata;
import io.warp10.crypto.CryptoUtils;
import io.warp10.crypto.KeyStore;
import io.warp10.crypto.OrderPreservingBase64;
import io.warp10.crypto.SipHashInline;
import io.warp10.quasar.encoder.QuasarTokenEncoder;
import io.warp10.quasar.token.thrift.data.ReadToken;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.apache.thrift.TException;
import org.apache.thrift.TSerializer;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TCompactProtocol.Factory;

import sun.security.tools.KeyStoreUtil;

import com.geoxp.GeoXPLib;
import com.google.common.base.Charsets;

/**
 * This class dumps the whole content of the store as
 * a set of datalog files in a directory.
 * 
 * This is used to allow resharding a sharded Warp 10 deployment.
 */
public class StandaloneDumper {
  
  private static final String DUMP_TTL = "dump.ttl";
  
  public static final void dump(DirectoryClient directory, StoreClient store, KeyStore keystore, String destdir) throws Exception {
    //
    // Identify the producer/owner/app tuples
    //
    
    List<String> classes = new ArrayList<String>();
    classes.add("~.*");
    
    List<Map<String,String>> labels = new ArrayList<Map<String,String>>();
    
    Map<String,String> labelsel = new HashMap<String, String>();
    labels.add(labelsel);
    
    System.out.println("Fetching Geo Time Series...");
    
    Iterator<Metadata> iter = directory.iterator(classes, labels);
    
    Set<List<String>> prodownapp = new HashSet<List<String>>();
  
    long[] classKeyLongs = SipHashInline.getKey(keystore.getKey(KeyStore.SIPHASH_CLASS));
    long[] labelsKeyLongs = SipHashInline.getKey(keystore.getKey(KeyStore.SIPHASH_LABELS));
  
    byte[] key = keystore.getKey(KeyStore.SIPHASH_WRAPPERS_PSK);
    
    long[] wrappersKeyLongs = null;
    
    if (null != key) {
      wrappersKeyLongs = SipHashInline.getKey(key);
    }
    
    Properties props = WarpConfig.getProperties();
    
    byte[] datalogPSK = keystore.decodeKey(props.getProperty(Configuration.DATALOG_PSK));
    
    int gtscount = 0;
    
    while(iter.hasNext()) {
      Metadata meta = iter.next();
      gtscount++;
      
      List<String> list = new ArrayList<String>();

      list.add(meta.getLabels().get(Constants.PRODUCER_LABEL));
      list.add(meta.getLabels().get(Constants.OWNER_LABEL));
      list.add(meta.getLabels().get(Constants.APPLICATION_LABEL));
      
      prodownapp.add(list);
    }
    
    System.out.println("Fetched " + gtscount + " Geo Time Series in " + prodownapp.size() + " (producer,owner,app) tuples.");
    
    //
    // Loop over producer/owner/application tuples
    //
    
    QuasarTokenEncoder qte = new QuasarTokenEncoder();

    long ttl = Long.parseLong(props.getProperty(DUMP_TTL, Long.toString(365 * 86400 * 1000L)));
    
    long otp;
    
    int idx = 1;
    
    long datapoints = 0;
    long nano = System.nanoTime();
    
    for (List<String> tuple: prodownapp) {
      System.out.print("Dumping tuple " + idx + " / " + prodownapp.size());
      idx++;
      
      //
      // Craft tokens
      //
      
      String producerUID = tuple.get(0);
      String ownerUID = tuple.get(1);
      String appName = tuple.get(2);
      
      String wtoken = qte.deliverWriteToken(appName, producerUID, ownerUID, ttl, keystore);
      
      List<String> apps = new ArrayList<String>();
      apps.add(appName);
      
      String rtoken = qte.deliverReadToken(appName, producerUID, ownerUID, apps, ttl, keystore);
      
      //
      // Fetch the metadata
      //
     
      labelsel.put(Constants.PRODUCER_LABEL, producerUID);
      labelsel.put(Constants.OWNER_LABEL, ownerUID);
      labelsel.put(Constants.APPLICATION_LABEL, appName);
      
      List<Metadata> metas = directory.find(classes, labels);
      
      //
      // Now fetch decoders
      //
      
      ReadToken token = Tokens.extractReadToken(rtoken);
      
      GTSDecoderIterator decoders = store.fetch(token, metas, Long.MAX_VALUE, Long.MIN_VALUE + 1, false, false);
      
      //
      // Create the output file. We obfuscate producer/owner
      //
      
      UUID uuid = UUID.fromString(producerUID);
      byte[] sbytes = producerUID.getBytes(Charsets.UTF_8);
      otp = SipHashInline.hash24(42L, 42L, sbytes, 0, sbytes.length);
      String path = new UUID(uuid.getMostSignificantBits() ^ otp, uuid.getLeastSignificantBits() ^ otp).toString();
      path = path + ".";
      uuid = UUID.fromString(ownerUID);
      sbytes = ownerUID.getBytes(Charsets.UTF_8);
      otp = SipHashInline.hash24(42L, 42L, sbytes, 0, sbytes.length);
      path = path + new UUID(uuid.getMostSignificantBits() ^ otp, uuid.getLeastSignificantBits() ^ otp).toString();
      path = path + ".";
      path = path + new String(OrderPreservingBase64.encode(appName.getBytes(Charsets.UTF_8)), Charsets.UTF_8);
      path = path + ".datalog";
      
      File out = new File(destdir, path + ".pending");
      
      PrintWriter pw = new PrintWriter(out);
      
      DatalogRequest dr = new DatalogRequest();
      dr.setTimestamp(TimeSource.getNanoTime());
      dr.setToken(wtoken);
      dr.setType(Constants.DATALOG_UPDATE);
      dr.setId(props.getProperty(Configuration.DATALOG_ID));
      
      //
      // Output DatalogRequest
      //
      
      TSerializer ser = new TSerializer(new TCompactProtocol.Factory());
      
      byte[] encoded;
      
      try {
        encoded = ser.serialize(dr);
      } catch (TException te) {
        throw new IOException(te);
      }
      
      if (null != datalogPSK) {
        encoded = CryptoUtils.wrap(datalogPSK, encoded);
      }
      
      encoded = OrderPreservingBase64.encode(encoded);

      pw.print("#");
      pw.println(new String(encoded, Charsets.US_ASCII));
           
      while(decoders.hasNext()) {
        GTSDecoder decoder = decoders.next();
      
        datapoints += decoder.getCount();
        
        boolean first = true;
        StringBuilder sb = new StringBuilder();
        
        if (null != wrappersKeyLongs) {
          decoder.next();
          GTSEncoder encoder = decoder.getEncoder(true);
          GTSWrapper wrapper = GTSWrapperHelper.fromGTSEncoderToGTSWrapper(encoder, true);
          byte[] opb64 = OrderPreservingBase64.encode(ser.serialize(wrapper));
          // Compute SipHash
          long hash = SipHashInline.hash24(wrappersKeyLongs[0], wrappersKeyLongs[1], opb64, 0, opb64.length);
          // Output shardkey 128BITS
          long shardkey =  (decoder.getMetadata().getClassId() & 0xFFFF0000L) | (decoder.getMetadata().getLabelsId() & 0xFFFFL);
          pw.print("#K");
          pw.println(shardkey);
          // Output comment with GTS (with a space so we don't risk having a comment starting in '#K'
          sb.append ("# ");
          GTSHelper.metadataToString(sb, decoder.getName(), decoder.getMetadata().getLabels());
          GTSHelper.metadataToString(sb, "", decoder.getMetadata().getAttributes());
          sb.append("\n");
          sb.append("0// _{} W:");
          
          String hex = Long.toHexString(hash);
          for (int i = 0; i < 16 - hex.length(); i++) {
            sb.append("0");            
          }
          sb.append(hex);
          sb.append(":");
          sb.append(new String(opb64, Charsets.US_ASCII));
          sb.append("\n");
          pw.print(sb.toString());
          continue;
        }
        
        while(decoder.next()) {
          long ts = decoder.getTimestamp();
          long location = decoder.getLocation();
          long elevation = decoder.getElevation();
          Object value = decoder.getValue();
          
          if (first) {
            first = false;
            // Output shardkey
            long shardkey =  (decoder.getMetadata().getClassId() & 0xFFFF0000L) | (decoder.getMetadata().getLabelsId() & 0xFFFFL);
            pw.print("#K");
            pw.println(shardkey);
            
            pw.print(ts);
            pw.print("/");
            
            if (GeoTimeSerie.NO_LOCATION != location) {
              double[] latlon = GeoXPLib.fromGeoXPPoint(location);
              pw.print(latlon[0]);
              pw.print(":");
              pw.print(latlon[1]);
            }
            
            pw.print("/");
            
            if (GeoTimeSerie.NO_ELEVATION != elevation) {
              pw.print(elevation);              
            }
            
            pw.print(" ");
            
            sb.setLength(0);
            GTSHelper.metadataToString(sb, decoder.getMetadata().getName(), decoder.getMetadata().getLabels());
            pw.print(sb.toString());
            pw.print(" ");
          } else {
            pw.print("=");
            pw.print(ts);
            pw.print("/");
            
            if (GeoTimeSerie.NO_LOCATION != location) {
              double[] latlon = GeoXPLib.fromGeoXPPoint(location);
              pw.print(latlon[0]);
              pw.print(":");
              pw.print(latlon[1]);
            }
            
            pw.print("/");
            
            if (GeoTimeSerie.NO_ELEVATION != elevation) {
              pw.print(elevation);              
            }
            
            pw.print(" ");            
          }
          
          
          sb.setLength(0);
          GTSHelper.encodeValue(sb, value);
          pw.println(value);
        }
      }
      
      pw.close();
      
      out.renameTo(new File(destdir, path));
    }
    
    nano = System.nanoTime() - nano;
    
    System.out.println("Dumped " + datapoints + " datapoints in " + (nano / 1000000.0D) + " ms.");
  }
}
