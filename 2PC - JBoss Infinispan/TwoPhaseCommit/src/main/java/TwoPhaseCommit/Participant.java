    package TwoPhaseCommit;

import TwoPhaseCommit.LockFileDemo.TransactionDecision;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.infinispan.Cache;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryCreated;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryModified;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryRemoved;
import org.infinispan.notifications.cachelistener.event.CacheEntryEvent;
import org.infinispan.remoting.transport.Address;


public class Participant {

    private Cache<String, String> coordinatorCache;
    private Cache<Address, String> sitesCache;
    private FileLock lock = null;
    private String result = "";
    private static Integer mutex;
    
    public boolean performTwoPhaseCommit() {
        //create cache manager
        EmbeddedCacheManager embeddedCacheManager = null;
        try {
            embeddedCacheManager = new DefaultCacheManager("infinispan.xml");
        } catch (IOException ex) {
            Logger.getLogger(Coordinator.class.getName()).log(Level.SEVERE, null, ex);
        }
        
        //getting coordinator and sites caches
        coordinatorCache = embeddedCacheManager.getCache("coordinator cache");
        sitesCache = embeddedCacheManager.getCache("sites cache");
        
        //setting listener for watching changes in coordinator cache
        CoordinatorListener coordinatorListener = new CoordinatorListener();
        coordinatorCache.addListener(coordinatorListener);
        
        //initialize synchronization primtive for result change
        mutex = new Integer(-1);
        
        //waiting for the listener to set the result value
        synchronized (mutex) {
            try {
                mutex.wait();
            } catch (InterruptedException e) {
                Logger.getLogger(Coordinator.class.getName()).log(Level.SEVERE, null, e);
            }
        }
        
        //stopping the cache manager
        embeddedCacheManager.stop();
        
        //return true if the transaction was commited
        return "commited".equals(result);
    }

    public static void twoPhaseCommitTest() {
        Participant participant = new Participant();

        boolean result = participant.performTwoPhaseCommit();

        if (result) {
            System.out.println("Transaction was commited.");
        } else {
            System.out.println("Transaction was aborted.");
        }
    }
    
    @Listener (sync = false)
    @SuppressWarnings("unused")
    private class CoordinatorListener {        
        @CacheEntryCreated
        @CacheEntryModified
        @CacheEntryRemoved
        public synchronized void addSitesDecision(CacheEntryEvent e) {
            switch ((String) e.getKey()) {
                case "request": {
                    if ("canCommit?".equals((String) e.getValue())) {
                        decideAndReportTransactionDecision();
                    }
                    break;
                }
                case "decision": {
                    //set result
                    result = (String) e.getValue();
                    
                    //notify the participant of the result
                    synchronized (mutex) {
                        mutex.notify();
                    }

                    //released locked resources
                    if (lock != null) {
                        LockFileDemo.releaseLock(lock);
                    }
                }
            }
        }
        
        private void decideAndReportTransactionDecision() {
            String decision = null;
            if (TransactionDecision.commit.equals(LockFileDemo.decideTransaction())) {
                decision = "commit";
                lock = LockFileDemo.lockFile();
            } else {
                decision = "abort";
            }
            sitesCache.put(sitesCache.getCacheManager().getAddress(), decision);
        }
    }
    
    public static void main(String[] argc) {
        twoPhaseCommitTest();
    }
}
