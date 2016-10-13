//
//   Copyright 2016  Cityzen Data
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
import io.warp10.continuum.LogUtil;
import io.warp10.continuum.TimeSource;
import io.warp10.continuum.Tokens;
import io.warp10.continuum.egress.EgressFetchHandler;
import io.warp10.continuum.gts.GTSHelper;
import io.warp10.continuum.ingress.DatalogForwarder;
import io.warp10.continuum.sensision.SensisionConstants;
import io.warp10.continuum.store.Constants;
import io.warp10.continuum.store.StoreClient;
import io.warp10.continuum.store.thrift.data.DatalogRequest;
import io.warp10.continuum.store.thrift.data.Metadata;
import io.warp10.continuum.thrift.data.LoggingEvent;
import io.warp10.crypto.CryptoUtils;
import io.warp10.crypto.KeyStore;
import io.warp10.crypto.OrderPreservingBase64;
import io.warp10.crypto.SipHashInline;
import io.warp10.quasar.token.thrift.data.WriteToken;
import io.warp10.script.WarpScriptException;
import io.warp10.sensision.Sensision;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLDecoder;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.output.FileWriterWithEncoding;
import org.apache.thrift.TDeserializer;
import org.apache.thrift.TException;
import org.apache.thrift.TSerializer;
import org.apache.thrift.protocol.TCompactProtocol;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;

public class StandaloneDeleteHandler extends AbstractHandler {
  
  private static final Logger LOG = LoggerFactory.getLogger(StandaloneDeleteHandler.class);

  private final KeyStore keyStore;
  private final StoreClient storeClient;
  private final StandaloneDirectoryClient directoryClient;
  
  private final byte[] classKey;
  private final byte[] labelsKey;  
  
  /**
   * Key to wrap the token in the file names
   */
  private final byte[] datalogPSK;
  
  private final long[] classKeyLongs;
  private final long[] labelsKeyLongs;
  
  private DateTimeFormatter fmt = ISODateTimeFormat.dateTimeParser();

  private final File loggingDir;
  
  private final String datalogId;
  
  private final DateTimeFormatter dtf = DateTimeFormat.forPattern("yyyyMMdd'T'HHmmss.SSS").withZoneUTC();

  public StandaloneDeleteHandler(KeyStore keystore, StandaloneDirectoryClient directoryClient, StoreClient storeClient) {
    this.keyStore = keystore;
    this.storeClient = storeClient;
    this.directoryClient = directoryClient;
    
    this.classKey = this.keyStore.getKey(KeyStore.SIPHASH_CLASS);
    this.classKeyLongs = SipHashInline.getKey(this.classKey);
    
    this.labelsKey = this.keyStore.getKey(KeyStore.SIPHASH_LABELS);
    this.labelsKeyLongs = SipHashInline.getKey(this.labelsKey);
    
    Properties props = WarpConfig.getProperties();
    
    if (props.containsKey(Configuration.DATALOG_DIR)) {
      File dir = new File(props.getProperty(Configuration.DATALOG_DIR));
      
      if (!dir.exists()) {
        throw new RuntimeException("Data logging target '" + dir + "' does not exist.");
      } else if (!dir.isDirectory()) {
        throw new RuntimeException("Data logging target '" + dir + "' is not a directory.");
      } else {
        loggingDir = dir;
      }
      
      String id = props.getProperty(Configuration.DATALOG_ID);
      
      if (null == id) {
        throw new RuntimeException("Property '" + Configuration.DATALOG_ID + "' MUST be set to a unique value for this instance.");
      } else {
        datalogId = new String(OrderPreservingBase64.encode(id.getBytes(Charsets.UTF_8)), Charsets.US_ASCII);
      }
    } else {
      loggingDir = null;
      datalogId = null;
    }
    
    if (props.containsKey(Configuration.DATALOG_PSK)) {
      this.datalogPSK = this.keyStore.decodeKey(props.getProperty(Configuration.DATALOG_PSK));
    } else {
      this.datalogPSK = null;
    }
  }
  
  @Override
  public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
    
    if (target.equals(Constants.API_ENDPOINT_DELETE)) {
      baseRequest.setHandled(true);
    } else {
      return;
    }    
    
    //
    // CORS header
    //
    
    response.setHeader("Access-Control-Allow-Origin", "*");
    
    long nano = System.nanoTime();
    
    //
    // Extract DatalogRequest if specified
    //
          
    String datalogHeader = request.getHeader(Constants.getHeader(Configuration.HTTP_HEADER_DATALOG));
    
    DatalogRequest dr = null;
    
    if (null != datalogHeader) {
      byte[] bytes = OrderPreservingBase64.decode(datalogHeader.getBytes(Charsets.US_ASCII));
      
      if (null != datalogPSK) {
        bytes = CryptoUtils.unwrap(datalogPSK, bytes);
      }
      
      if (null == bytes) {
        throw new IOException("Invalid Datalog header.");
      }
        
      TDeserializer deser = new TDeserializer(new TCompactProtocol.Factory());
        
      try {
        dr = new DatalogRequest();
        deser.deserialize(dr, bytes);
      } catch (TException te) {
        throw new IOException();
      }
      
      //
      // Check that the request query string matches the QS in the datalog request
      //
      
      if (!request.getQueryString().equals(dr.getDeleteQueryString())) {
        throw new IOException("Invalid DatalogRequest.");
      }
    }
    
    //
    // TODO(hbs): Extract producer/owner from token
    //
    
    String token = null != dr ? dr.getToken() : request.getHeader(Constants.getHeader(Configuration.HTTP_HEADER_TOKENX));
            
    WriteToken writeToken;
    
    try {
      writeToken = Tokens.extractWriteToken(token);
    } catch (WarpScriptException ee) {
      throw new IOException(ee);
    }
    
    String application = writeToken.getAppName();
    String producer = Tokens.getUUID(writeToken.getProducerId());
    String owner = Tokens.getUUID(writeToken.getOwnerId());
      
    //
    // For delete operations, producer and owner MUST be equal
    //
    
    if (!producer.equals(owner)) {
      throw new IOException("Invalid write token for deletion.");
    }
    
    Map<String,String> sensisionLabels = new HashMap<String,String>();
    sensisionLabels.put(SensisionConstants.SENSISION_LABEL_PRODUCER, producer);

    long count = 0;
    long gts = 0;
    
    Throwable t = null;
    StringBuilder metas = new StringBuilder();

    //
    // Extract start/end
    //
    
    String startstr = request.getParameter(Constants.HTTP_PARAM_START);
    String endstr = request.getParameter(Constants.HTTP_PARAM_END);
          
    //
    // Extract selector
    //
    
    String selector = request.getParameter(Constants.HTTP_PARAM_SELECTOR);
    
    String minage = request.getParameter(Constants.HTTP_PARAM_MINAGE);
    
    if (null != minage) {
      throw new IOException("Standalone version does not support the '" + Constants.HTTP_PARAM_MINAGE + "' parameter in delete requests.");
    }
    
    boolean dryrun = null != request.getParameter(Constants.HTTP_PARAM_DRYRUN);
    
    File loggingFile = null;
    PrintWriter loggingWriter = null;
    
    //
    // Open the logging file if logging is enabled
    //
    
    if (null != loggingDir) {
      long nanos = null != dr ? dr.getTimestamp() : TimeSource.getNanoTime();
      StringBuilder sb = new StringBuilder();
      sb.append(Long.toHexString(nanos));
      sb.insert(0, "0000000000000000", 0, 16 - sb.length());
      sb.append("-");
      if (null != dr) {
        sb.append(dr.getId());
      } else {
        sb.append(datalogId);
      }

      sb.append("-");
      sb.append(dtf.print(nanos / 1000000L));
      sb.append(Long.toString(1000000L + (nanos % 1000000L)).substring(1));
      sb.append("Z");
      
      if (null == dr) {
        dr = new DatalogRequest();
        dr.setTimestamp(nanos);
        dr.setType(Constants.DATALOG_DELETE);
        dr.setId(datalogId);
        dr.setToken(token); 
        dr.setDeleteQueryString(request.getQueryString());
      }
      
      //
      // Serialize the request
      //
      
      TSerializer ser = new TSerializer(new TCompactProtocol.Factory());
      
      byte[] encoded;
      
      try {
        encoded = ser.serialize(dr);
      } catch (TException te) {
        throw new IOException(te);
      }
      
      if (null != this.datalogPSK) {
        encoded = CryptoUtils.wrap(this.datalogPSK, encoded);
      }
      
      encoded = OrderPreservingBase64.encode(encoded);
              
      loggingFile = new File(loggingDir, sb.toString());
      loggingWriter = new PrintWriter(new FileWriterWithEncoding(loggingFile, Charsets.UTF_8));
      
      //
      // Write request
      //
      
      loggingWriter.println(new String(encoded, Charsets.US_ASCII));
    }

    boolean validated = false;
    
    try {      
      if (null == producer || null == owner) {
        response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid token.");
        return;
      }
      
      //
      // Build extra labels
      //
      
      Map<String,String> extraLabels = new HashMap<String,String>();
      //
      // Only set owner and potentially app, producer may vary
      //      
      extraLabels.put(Constants.OWNER_LABEL, owner);
      // FIXME(hbs): remove me
      if (null != application) {
        extraLabels.put(Constants.APPLICATION_LABEL, application);
        sensisionLabels.put(SensisionConstants.SENSISION_LABEL_APPLICATION, application);
      }

      boolean hasRange = false;
      
      long start = Long.MIN_VALUE;
      long end = Long.MAX_VALUE;
      
      if (null != startstr) {
        if (null == endstr) {
          throw new IOException("Both " + Constants.HTTP_PARAM_START + " and " + Constants.HTTP_PARAM_END + " should be defined.");
        }
        if (startstr.contains("T")) {
          start = fmt.parseDateTime(startstr).getMillis() * Constants.TIME_UNITS_PER_MS;
        } else {
          start = Long.valueOf(startstr);
        }
      }
      
      if (null != endstr) {
        if (null == startstr) {
          throw new IOException("Both " + Constants.HTTP_PARAM_START + " and " + Constants.HTTP_PARAM_END + " should be defined.");
        }
        if (endstr.contains("T")) {
          end = fmt.parseDateTime(endstr).getMillis() * Constants.TIME_UNITS_PER_MS;          
        } else {
          end = Long.valueOf(endstr);
        }
      }
      
      if (Long.MIN_VALUE == start && Long.MAX_VALUE == end && null == request.getParameter(Constants.HTTP_PARAM_DELETEALL)) {
        throw new IOException("Parameter " + Constants.HTTP_PARAM_DELETEALL + " should be set when deleting a full range.");
      }
      
      if (Long.MIN_VALUE != start || Long.MAX_VALUE != end) {
        hasRange = true;
      }
      
      if (start > end) {
        throw new IOException("Invalid time range specification.");
      }
      
      //
      // Extract the class and labels selectors
      // The class selector and label selectors are supposed to have
      // values which use percent encoding, i.e. explicit percent encoding which
      // might have been re-encoded using percent encoding when passed as parameter
      //
      //
      
      Matcher m = EgressFetchHandler.SELECTOR_RE.matcher(selector);
      
      if (!m.matches()) {
        response.sendError(HttpServletResponse.SC_BAD_REQUEST);
        return;
      }
      
      String classSelector = URLDecoder.decode(m.group(1), "UTF-8");
      String labelsSelection = m.group(2);
      
      Map<String,String> labelsSelectors;

      try {
        labelsSelectors = GTSHelper.parseLabelsSelectors(labelsSelection);
      } catch (ParseException pe) {
        throw new IOException(pe);
      }
      
      validated = true;
      
      //
      // Force 'producer'/'owner'/'app' from token
      //
      
      labelsSelectors.putAll(extraLabels);

      List<Metadata> metadatas = null;
      
      List<String> clsSels = new ArrayList<String>();
      List<Map<String,String>> lblsSels = new ArrayList<Map<String,String>>();
      clsSels.add(classSelector);
      lblsSels.add(labelsSelectors);
      
      metadatas = directoryClient.find(clsSels, lblsSels);

      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentType("text/plain");
      
      PrintWriter pw = response.getWriter();
      StringBuilder sb = new StringBuilder();
      
      for (Metadata metadata: metadatas) {        
        //
        // Remove from DB
        //
       
        if (!hasRange) {
          if (!dryrun) {
            this.directoryClient.unregister(metadata);
          }
        }
        
        //
        // Remove data
        //
        
        long localCount = 0;
        
        if (!dryrun) {
          localCount = this.storeClient.delete(writeToken, metadata, start, end);
        }

        count += localCount;

        sb.setLength(0);
        GTSHelper.metadataToString(sb, metadata.getName(), metadata.getLabels());
        
        if (metadata.getAttributesSize() > 0) {
          GTSHelper.labelsToString(sb, metadata.getAttributes());
        } else {
          sb.append("{}");
        }
        
        pw.write(sb.toString());
        pw.write("\r\n");
        metas.append(sb);
        metas.append("\n");
        gts++;

        // Log detailed metrics for this GTS owner and app
        Map<String, String> labels = new HashMap<>();
        labels.put(SensisionConstants.SENSISION_LABEL_OWNER, metadata.getLabels().get(Constants.OWNER_LABEL));
        labels.put(SensisionConstants.SENSISION_LABEL_APPLICATION, metadata.getLabels().get(Constants.APPLICATION_LABEL));
        Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_STANDALONE_DELETE_DATAPOINTS_PEROWNERAPP, labels, localCount);
      }
    } catch (Exception e) {
      t = e;
      throw e;
    } finally {
      if (null != loggingWriter) {
        loggingWriter.close();
        if (validated) {
          loggingFile.renameTo(new File(loggingFile.getAbsolutePath() + DatalogForwarder.DATALOG_SUFFIX));
        } else {
          loggingFile.delete();
        }
      }      

      Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_STANDALONE_DELETE_REQUESTS, sensisionLabels, 1);
      Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_STANDALONE_DELETE_GTS, sensisionLabels, gts);
      Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_STANDALONE_DELETE_DATAPOINTS, sensisionLabels, count);
      Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_STANDALONE_DELETE_TIME_US, sensisionLabels, (System.nanoTime() - nano) / 1000);
      
      LoggingEvent event = LogUtil.setLoggingEventAttribute(null, LogUtil.DELETION_TOKEN, token);
      event = LogUtil.setLoggingEventAttribute(event, LogUtil.DELETION_SELECTOR, selector);
      event = LogUtil.setLoggingEventAttribute(event, LogUtil.DELETION_START, startstr);
      event = LogUtil.setLoggingEventAttribute(event, LogUtil.DELETION_END, endstr);
      event = LogUtil.setLoggingEventAttribute(event, LogUtil.DELETION_METADATA, metas.toString());
      event = LogUtil.setLoggingEventAttribute(event, LogUtil.DELETION_COUNT, Long.toString(count));
      event = LogUtil.setLoggingEventAttribute(event, LogUtil.DELETION_GTS, Long.toString(gts));
      
      if (null != t) {
        LogUtil.setLoggingEventStackTrace(null, LogUtil.STACK_TRACE, t);
      }
      
      LOG.info(LogUtil.serializeLoggingEvent(this.keyStore, event));
    }

    response.setStatus(HttpServletResponse.SC_OK);
  }  
}
