package io.warp10.standalone;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import org.fusesource.leveldbjni.JniDBFactory;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBException;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.Range;
import org.iq80.leveldb.ReadOptions;
import org.iq80.leveldb.Snapshot;
import org.iq80.leveldb.WriteBatch;
import org.iq80.leveldb.WriteOptions;
import org.iq80.leveldb.impl.Filename;
import org.iq80.leveldb.impl.Iq80DBFactory;

public class WarpDB implements DB {
  
  private DB db;

  private AtomicInteger pendingOps = new AtomicInteger(0);
  private AtomicBoolean compactionsSuspended = new AtomicBoolean(false);
  
  private ReentrantLock mutex = new ReentrantLock();
  
  private final boolean nativedisabled;
  private final boolean javadisabled;
  private final String home;
  private final Options options;
  
  private static final class WarpIterator implements DBIterator {

    private final DBIterator iter;
    private final AtomicInteger count;
    
    public WarpIterator(AtomicInteger count, DBIterator iter) {
      this.count = count;
      this.iter = iter;
    }
    
    @Override
    public boolean hasNext() {
      return this.iter.hasNext();
    }
    
    @Override
    public void close() throws IOException {
      try {
        this.iter.close();
      } finally {
        count.decrementAndGet();
      }
    }
    
    @Override
    public boolean hasPrev() {
      return this.iter.hasPrev();
    }
    
    @Override
    public Entry<byte[], byte[]> peekNext() {
      return this.iter.peekNext();
    }
    
    @Override
    public Entry<byte[], byte[]> peekPrev() {
      return this.iter.peekPrev();
    }
    
    @Override
    public Entry<byte[], byte[]> next() {
      return this.iter.next();
    }
    
    @Override
    public Entry<byte[], byte[]> prev() {
      return this.iter.prev();
    }
    
    @Override
    public void seek(byte[] key) {
      this.iter.seek(key);
    }
    
    @Override
    public void seekToFirst() {
      this.iter.seekToFirst();
    }
    
    @Override
    public void seekToLast() {
      this.iter.seekToLast();
    }    
  }
  
  public WarpDB(boolean nativedisabled, boolean javadisabled, String home, Options options) throws IOException {
    this.nativedisabled = nativedisabled;
    this.javadisabled = javadisabled;
    this.options = options;
    this.home = home;

    this.open(nativedisabled, javadisabled, home, options);
  }
  
  private synchronized void open(boolean nativedisabled, boolean javadisabled, String home, Options options) throws IOException {
    
    try {
      mutex.lockInterruptibly();
      
      // Wait for iterators and other ops to finish
      while(pendingOps.get() > 0) {
        LockSupport.parkNanos(100000000L);
      }
      
      if (null != db) {
        this.db.close();
      }
      
      try {
        if (!nativedisabled) {
          db = JniDBFactory.factory.open(new File(home), options);
        } else {
          throw new UnsatisfiedLinkError("Native LevelDB implementation disabled.");
        }
      } catch (UnsatisfiedLinkError ule) {
        ule.printStackTrace();
        if (!javadisabled) {
          System.out.println("WARNING: falling back to pure java implementation of LevelDB.");
          db = Iq80DBFactory.factory.open(new File(home), options);
        } else {
          throw new RuntimeException("No usable LevelDB implementation, aborting.");
        }
      }                
    } catch (InterruptedException ie) {
      throw new RuntimeException("Interrupted while opending LevelDB.", ie);
    } finally {
      if (mutex.isHeldByCurrentThread()) {
        mutex.unlock();
      }
    }
  }
  
  @Override
  public void close() throws IOException {
    this.db.close();
  }
  
  @Override
  public void compactRange(byte[] begin, byte[] end) throws DBException {
    try {
      mutex.lockInterruptibly();
      pendingOps.incrementAndGet();
    } catch (InterruptedException ie) {
      throw new DBException("Interrupted while acquiring DB mutex.", ie);
    } finally {
      if (mutex.isHeldByCurrentThread()) {
        mutex.unlock();
      }
    }
    try {
      this.db.compactRange(begin, end);
    } finally {
      this.pendingOps.decrementAndGet();
    }
  }
  
  @Override
  public WriteBatch createWriteBatch() {
    return this.db.createWriteBatch();
  }
  
  @Override
  public void delete(byte[] key) throws DBException {
    try {
      mutex.lockInterruptibly();
      pendingOps.incrementAndGet();
    } catch (InterruptedException ie) {
      throw new DBException("Interrupted while acquiring DB mutex.", ie);
    } finally {
      if (mutex.isHeldByCurrentThread()) {
        mutex.unlock();
      }
    }
    try {
      this.db.delete(key);
    } finally {
      this.pendingOps.decrementAndGet();
    }
  }
  
  @Override
  public Snapshot delete(byte[] key, WriteOptions options) throws DBException {
    if (options.snapshot()) {
      throw new RuntimeException("Snapshots are unsupported.");      
    }
    
    try {
      mutex.lockInterruptibly();
      pendingOps.incrementAndGet();
    } catch (InterruptedException ie) {
      throw new DBException("Interrupted while acquiring DB mutex.", ie);
    } finally {
      if (mutex.isHeldByCurrentThread()) {
        mutex.unlock();
      }
    }
    try {
      return this.db.delete(key, options);
    } finally {
      this.pendingOps.decrementAndGet();
    }
  }
  
  @Override
  public byte[] get(byte[] key) throws DBException {
    throw new RuntimeException("Unsupported operation get.");
    //return this.db.get(key);
  }
  
  @Override
  public byte[] get(byte[] key, ReadOptions options) throws DBException {
    throw new RuntimeException("Unsupported operation get.");
    //return this.db.get(key, options);
  }
  
  @Override
  public long[] getApproximateSizes(Range... ranges) {
    throw new RuntimeException("Unsupported operation getApproximateSizes.");
    //return this.db.getApproximateSizes(ranges);
  }
  
  @Override
  public String getProperty(String name) {
    return this.db.getProperty(name);
  }
  
  @Override
  public Snapshot getSnapshot() {
    throw new RuntimeException("Snapshots are unsupported.");
    //return this.db.getSnapshot();
  }
  
  @Override
  public DBIterator iterator() {
    try {
      mutex.lockInterruptibly();
      pendingOps.incrementAndGet();
    } catch (InterruptedException ie) {
      throw new DBException("Interrupted while acquiring DB mutex.", ie);
    } finally {
      if (mutex.isHeldByCurrentThread()) {
        mutex.unlock();
      }
    }
    return new WarpIterator(pendingOps, this.db.iterator());
  }
  
  @Override
  public DBIterator iterator(ReadOptions options) {
    if (null != options.snapshot()) {
      throw new RuntimeException("Snapshots are unsupported.");
    }
    try {
      mutex.lockInterruptibly();
      pendingOps.incrementAndGet();
    } catch (InterruptedException ie) {
      throw new DBException("Interrupted while acquiring DB mutex.", ie);
    } finally {
      if (mutex.isHeldByCurrentThread()) {
        mutex.unlock();
      }
    }    
    return new WarpIterator(pendingOps, this.db.iterator(options));
  }
  
  @Override
  public void put(byte[] key, byte[] value) throws DBException {
    try {
      mutex.lockInterruptibly();
      pendingOps.incrementAndGet();
    } catch (InterruptedException ie) {
      throw new DBException("Interrupted while acquiring DB mutex.", ie);
    } finally {
      if (mutex.isHeldByCurrentThread()) {
        mutex.unlock();
      }
    }
    try {
      this.db.put(key, value);
    } finally {
      this.pendingOps.decrementAndGet();
    }
  }
  
  @Override
  public Snapshot put(byte[] key, byte[] value, WriteOptions options) throws DBException {
    if (options.snapshot()) {
      throw new RuntimeException("Snapshots are unsupported.");      
    }
    
    try {
      mutex.lockInterruptibly();
      pendingOps.incrementAndGet();
    } catch (InterruptedException ie) {
      throw new DBException("Interrupted while acquiring DB mutex.", ie);
    } finally {
      if (mutex.isHeldByCurrentThread()) {
        mutex.unlock();
      }
    }
    try {
      return this.db.put(key, value, options);
    } finally {
      this.pendingOps.decrementAndGet();
    }
  }
  
  @Override
  public void resumeCompactions() {
    if (!compactionsSuspended.get()) {
      return;
    }
    this.db.resumeCompactions();
    compactionsSuspended.set(false);
  }
  
  @Override
  public void suspendCompactions() throws InterruptedException {
    if (compactionsSuspended.get()) {
      return;
    }
    try {
      mutex.lockInterruptibly();
      compactionsSuspended.set(true);
    } catch (InterruptedException ie) {
      throw new DBException("Interrupted while acquiring DB mutex.", ie);
    } finally {
      if (mutex.isHeldByCurrentThread()) {
        mutex.unlock();
      }
    }    
    try {
      this.db.suspendCompactions();
    } catch (Throwable t) {
      compactionsSuspended.set(false);
    }
  }
  
  @Override
  public void write(WriteBatch updates) throws DBException {
    try {
      mutex.lockInterruptibly();
      pendingOps.incrementAndGet();
    } catch (InterruptedException ie) {
      throw new DBException("Interrupted while acquiring DB mutex.", ie);
    } finally {
      if (mutex.isHeldByCurrentThread()) {
        mutex.unlock();
      }
    }
    try {
      this.db.write(updates);
    } finally {
      this.pendingOps.decrementAndGet();
    }
  }
  
  @Override
  public Snapshot write(WriteBatch updates, WriteOptions options) throws DBException {
    if (options.snapshot()) {
      throw new RuntimeException("Snapshots are unsupported.");      
    }
    
    try {
      mutex.lockInterruptibly();
      pendingOps.incrementAndGet();
    } catch (InterruptedException ie) {
      throw new DBException("Interrupted while acquiring DB mutex.", ie);
    } finally {
      if (mutex.isHeldByCurrentThread()) {
        mutex.unlock();
      }
    }
    try {
      return this.db.write(updates, options);
    } finally {
      this.pendingOps.decrementAndGet();
    }    
  }
  
  public void purge(List<Long> tables) throws IOException {
    try {
      mutex.lockInterruptibly();
      
      while(pendingOps.get() > 0 && compactionsSuspended.get()) {
        LockSupport.parkNanos(100000000L);
      }
      
      try {
        
        // Check that each element of 'tables' is a file which can be deleted
        
        for (long table: tables) {
          File sstfile = new File(home, Filename.tableFileName(table));
          if (!sstfile.exists() || !sstfile.isFile() || !sstfile.canWrite()) {
            throw new IOException("SST file '" + table + "' cannot be deleted.");
          }
        }

        // Close the db
        this.db.close();
        this.db = null;

        // Delete SST files
        for (long table: tables) {
          File sstfile = new File(home, Filename.tableFileName(table));
          sstfile.delete();
        }

        // Repair the LevelDB directory
        try {
          if (!nativedisabled) {
            JniDBFactory.factory.repair(new File(home), options);
          } else {
            throw new UnsatisfiedLinkError("Native LevelDB implementation disabled.");
          }
        } catch (UnsatisfiedLinkError ule) {
          ule.printStackTrace();
          if (!javadisabled) {
            Iq80DBFactory.factory.repair(new File(home), options);
          } else {
            throw new RuntimeException("No usable LevelDB implementation, aborting.");
          }
        }
        
        // Reopen LevelDB
        open(nativedisabled, javadisabled, home, options);
      } catch (Throwable t) {
        throw new RuntimeException("Exception when attempting DB repair.", t);
      }
    } catch (InterruptedException ie) {
      throw new DBException("Interrupted while acquiring DB mutex.", ie);
    } finally {
      if (mutex.isHeldByCurrentThread()) {
        mutex.unlock();
      }
    }
  }
}
