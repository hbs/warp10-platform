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

import io.warp10.continuum.TimeSource;
import io.warp10.continuum.sensision.SensisionConstants;
import io.warp10.sensision.Sensision;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.locks.LockSupport;

import org.iq80.leveldb.DB;

public class StandaloneSnapshotManager extends Thread {
  
  /**
   * File path to watch for triggering suspension of compactions 
   */
  private final String triggerPath;
  
  /**
   * File path to create to notify the external process that compactions are suspended
   */
  private final String signalPath;
  
  public StandaloneSnapshotManager(String triggerPath, String signalPath) {
    this.triggerPath = triggerPath;
    this.signalPath = signalPath;
  }
  
  @Override
  public void run() {
    while(true) {
      
      //
      // Exit if db is not set
      //
      
      if (null == Warp.getDB()) {
        break;
      }

      //
      // Sleep for 1s
      //
      
      LockSupport.parkNanos(1000000000);
      
      //
      // Check if the trigger file for backup exists
      //
      
      File trigger = new File(triggerPath);
      
      if (!trigger.exists()) {
        continue;
      }
      
      long nano = System.nanoTime();
      
      //
      // Trigger path exists, suspend compactions
      //
      
      WarpDB db = Warp.getDB();
      
      synchronized(db) {
        
        boolean interrupted = false;
        
        do {
          interrupted = false;
          try {
            db.suspendCompactions();
          } catch (InterruptedException ie) {
            interrupted = true;
          }
        } while (interrupted);
        
        //
        // Signal that compactions are suspended by creating the signalPath
        //
        
        File signal = new File(signalPath);
        
        try {
          signal.createNewFile();
        } catch (IOException ioe) {          
        }
        
        //
        // Wait until the trigger file has vanished
        //
        
        while(trigger.exists()) {
          LockSupport.parkNanos(100000000L);
        }
        
        //
        // Resume compactions
        //
        
        db.resumeCompactions();

        nano = System.nanoTime() - nano;
        
        Sensision.update(SensisionConstants.SENSISION_CLASS_WARP_STANDALONE_LEVELDB_SNAPSHOT_REQUESTS, Sensision.EMPTY_LABELS, 1);
        Sensision.update(SensisionConstants.SENSISION_CLASS_WARP_STANDALONE_LEVELDB_SNAPSHOT_TIME_NS, Sensision.EMPTY_LABELS, nano);
        
        //
        // Remove the signal file
        //
        
        signal.delete();
      }            
    }
  }
}
