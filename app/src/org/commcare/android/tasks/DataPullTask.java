package org.commcare.android.tasks;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.database.Cursor;
import android.net.http.AndroidHttpClient;
import android.util.Log;

import net.sqlcipher.database.SQLiteDatabase;

import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.conn.ConnectTimeoutException;
import org.commcare.android.crypt.CryptUtil;
import org.commcare.android.database.SqlStorage;
import org.commcare.android.database.app.models.UserKeyRecord;
import org.commcare.android.database.user.models.ACase;
import org.commcare.android.database.user.models.User;
import org.commcare.android.javarosa.AndroidLogger;
import org.commcare.android.net.HttpRequestGenerator;
import org.commcare.android.tasks.templates.CommCareTask;
import org.commcare.android.util.AndroidStreamUtil;
import org.commcare.android.util.AndroidStreamUtil.StreamReadObserver;
import org.commcare.android.util.CommCareUtil;
import org.commcare.android.util.SessionUnavailableException;
import org.commcare.android.util.bitcache.BitCache;
import org.commcare.android.util.bitcache.BitCacheFactory;
import org.commcare.cases.ledger.Ledger;
import org.commcare.cases.ledger.LedgerPurgeFilter;
import org.commcare.cases.util.CasePurgeFilter;
import org.commcare.dalvik.application.CommCareApp;
import org.commcare.dalvik.application.CommCareApplication;
import org.commcare.dalvik.odk.provider.FormsProviderAPI.FormsColumns;
import org.commcare.dalvik.services.CommCareSessionService;
import org.commcare.data.xml.DataModelPullParser;
import org.commcare.resources.model.CommCareOTARestoreListener;
import org.commcare.xml.CommCareTransactionParserFactory;
import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.model.instance.AbstractTreeElement;
import org.javarosa.core.model.instance.DataInstance;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.core.reference.InvalidReferenceException;
import org.javarosa.core.reference.ReferenceManager;
import org.javarosa.core.services.Logger;
import org.javarosa.core.services.storage.IStorageIterator;
import org.javarosa.core.services.storage.StorageFullException;
import org.javarosa.core.util.PropertyUtils;
import org.javarosa.model.xform.XPathReference;
import org.javarosa.xml.util.InvalidStructureException;
import org.javarosa.xml.util.UnfullfilledRequirementsException;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.Hashtable;
import java.util.NoSuchElementException;
import java.util.Vector;

import javax.crypto.SecretKey;

/**
 * @author ctsims
 */
public abstract class DataPullTask<R> extends CommCareTask<Void, Integer, Integer, R> implements CommCareOTARestoreListener {
    String server;
    String keyProvider;
    String username;
    String password;
    Context c;
    
    int mCurrentProgress = -1;
    int mTotalItems = -1;
    long mSyncStartTime;
    
    private boolean wasKeyLoggedIn = false;
    
    public static final int DATA_PULL_TASK_ID = 10;
    
    public static final int DOWNLOAD_SUCCESS = 0;
    public static final int AUTH_FAILED = 1;
    public static final int BAD_DATA = 2;
    public static final int UNKNOWN_FAILURE = 4;
    public static final int UNREACHABLE_HOST = 8;
    public static final int CONNECTION_TIMEOUT = 16;
    public static final int SERVER_ERROR = 32;
    
    public static final int PROGRESS_STARTED = 0;
    public static final int PROGRESS_CLEANED = 1;
    public static final int PROGRESS_AUTHED = 2;
    public static final int PROGRESS_DONE= 4;
    public static final int PROGRESS_RECOVERY_NEEDED= 8;
    public static final int PROGRESS_RECOVERY_STARTED= 16;
    public static final int PROGRESS_RECOVERY_FAIL_SAFE = 32;
    public static final int PROGRESS_RECOVERY_FAIL_BAD = 64;
    public static final int PROGRESS_PROCESSING = 128;
    public static final int PROGRESS_DOWNLOADING = 256;
    
    /**
     * Whether to enable loading this data from a local asset for 
     * debug/testing. 
     * 
     * This flag should never be set to true on a prod build or in VC
     * TODO: It should be an error for "debuggable" to be off and this flag
     * to be true
     */
    private static final boolean DEBUG_LOAD_FROM_LOCAL = false;
    private InputStream mDebugStream;

    public DataPullTask(String username, String password, String server, String keyProvider, Context c) {
        this.server = server;
        this.keyProvider = keyProvider;
        this.username = username;
        this.password = password;
        this.c = c;
        this.taskId = DATA_PULL_TASK_ID;

        TAG = DataPullTask.class.getSimpleName();
    }

    @Override
    protected void onCancelled() {
        super.onCancelled();
        if(wasKeyLoggedIn) {
            CommCareApplication._().releaseUserResourcesAndServices();
        }
    }
    
    @Override
    protected Integer doTaskBackground(Void... params) {
        // Don't try to sync if logging out is occuring
        if (!CommCareSessionService.sessionAliveLock.tryLock()) {
            // TODO PLM: once this task is refactored into manageable
            // components, it should use the ManagedAsyncTask pattern of
            // checking for isCancelled() and aborting at safe places.
            return UNKNOWN_FAILURE;
        }


        // Wrap in a 'try' to enable a 'finally' close that releases the
        // sessionAliveLock.
        try {
        publishProgress(PROGRESS_STARTED);
        CommCareApp app = CommCareApplication._().getCurrentApp();
        SharedPreferences prefs = app.getAppPreferences();
        
        String keyServer = prefs.getString("key_server", null);
        
        mTotalItems = -1;
        mCurrentProgress = -1;
        
        //Whether or not we should be generating the first key
        boolean useExternalKeys  = !(keyServer == null || keyServer.equals(""));
        
        boolean loginNeeded = true;
        boolean useRequestFlags = false;
        try {
            loginNeeded = !CommCareApplication._().getSession().isActive();
        } catch(SessionUnavailableException sue) {
            //expected if we aren't initialized.
        }
        
        int responseError = UNKNOWN_FAILURE;
        
        //This should be per _user_, not per app
        prefs.edit().putLong("last-ota-restore", new Date().getTime()).commit();
        
        HttpRequestGenerator requestor = new HttpRequestGenerator(username, password);
        
        CommCareTransactionParserFactory factory = new CommCareTransactionParserFactory(c, requestor) {
            boolean publishedAuth = false;
            @Override
            public void reportProgress(int progress) {
                if(!publishedAuth) {
                    DataPullTask.this.publishProgress(PROGRESS_AUTHED,progress);
                    publishedAuth = true;
                }
            }
        };
        Logger.log(AndroidLogger.TYPE_USER, "Starting Sync");
        long bytesRead = -1;

        UserKeyRecord ukr = null;
            
            try {
                //This is a dangerous way to do this (the null settings), should revisit later
                if(loginNeeded) {
                    if(!useExternalKeys) {
                        //Get the key 
                        SecretKey newKey = CryptUtil.generateSemiRandomKey();
                        
                        if(newKey == null) {
                            this.publishProgress(PROGRESS_DONE);
                            return UNKNOWN_FAILURE;
                        }
                        String sandboxId = PropertyUtils.genUUID().replace("-", "");
                        ukr = new UserKeyRecord(username, UserKeyRecord.generatePwdHash(password), CryptUtil.wrapKey(newKey.getEncoded(),password), new Date(), new Date(Long.MAX_VALUE), sandboxId);
                        
                    } else {
                        ukr = ManageKeyRecordTask.getCurrentValidRecord(app, username, password, true);
                        if(ukr == null) {
                            Logger.log(AndroidLogger.TYPE_ERROR_ASSERTION, "Shouldn't be able to not have a valid key record when OTA restoring with a key server");
                            this.publishProgress(PROGRESS_DONE);
                            return UNKNOWN_FAILURE;
                        }
                    }
                    
                    //add to transaction parser factory
                    byte[] wrappedKey = CryptUtil.wrapKey(ukr.getEncryptedKey(),password);
                    factory.initUserParser(wrappedKey);
                } else {
                    factory.initUserParser(CommCareApplication._().getSession().getLoggedInUser().getWrappedKey());
                    
                    //Only purge cases if we already had a logged in user. Otherwise we probably can't read the DB.
                    purgeCases();
                    useRequestFlags = true;
                }
                //Either way, don't re-do this step
                this.publishProgress(PROGRESS_CLEANED);

                int responseCode = -1;
                HttpResponse response = null;
                
                //This isn't awesome, but it's hard to work this in in a cleaner way
                if(DEBUG_LOAD_FROM_LOCAL) {
                    try {
                        mDebugStream = ReferenceManager._().DeriveReference("jr://asset/payload.xml").getStream();
                    } catch(InvalidReferenceException ire) {
                        throw new IOException("No payload available at jr://asset/payload.xml");
                    }
                    responseCode = 200;
                } else {
                    response = requestor.makeCaseFetchRequest(server, useRequestFlags);
                    responseCode = response.getStatusLine().getStatusCode();
                }
                Logger.log(AndroidLogger.TYPE_USER, "Request opened. Response code: " + responseCode);
                
                if(responseCode == 401) {
                    //If we logged in, we need to drop those credentials
                    if(loginNeeded) {
                        CommCareApplication._().releaseUserResourcesAndServices();
                    }
                    Logger.log(AndroidLogger.TYPE_USER, "Bad Auth Request for user!|" + username);
                    return AUTH_FAILED;
                } else if(responseCode >= 200 && responseCode < 300) {
                    
                    if(loginNeeded) {                        
                        //This is necessary (currently) to make sure that data
                        //is encoded. Probably a better way to do this.
                        CommCareApplication._().startUserSession(CryptUtil.unWrapKey(ukr.getEncryptedKey(), password), ukr);
                        wasKeyLoggedIn = true;
                    }
                    
                    
                    this.publishProgress(PROGRESS_AUTHED,0);
                    Logger.log(AndroidLogger.TYPE_USER, "Remote Auth Successful|" + username);
                                        
                    try {
                        BitCache cache = writeResponseToCache(response);
                        
                        InputStream cacheIn = cache.retrieveCache();
                        String syncToken = readInput(cacheIn, factory);
                        updateUserSyncToken(syncToken);
                        
                        //record when we last synced
                        Editor e = prefs.edit();
                        e.putLong("last-succesful-sync", new Date().getTime());
                        e.commit();
                        
                        if(loginNeeded) {                        
                            CommCareApplication._().getAppStorage(UserKeyRecord.class).write(ukr);
                        }
                        
                        //Let anyone who is listening know!
                        Intent i = new Intent("org.commcare.dalvik.api.action.data.update");
                        this.c.sendBroadcast(i);
                        
                        Logger.log(AndroidLogger.TYPE_USER, "User Sync Successful|" + username);
                        this.publishProgress(PROGRESS_DONE);
                        return DOWNLOAD_SUCCESS;
                    } catch (InvalidStructureException e) {
                        e.printStackTrace();
                        
                        //TODO: Dump more details!!!
                        Logger.log(AndroidLogger.TYPE_USER, "User Sync failed due to bad payload|" + e.getMessage());
                        return BAD_DATA;
                    } catch (XmlPullParserException e) {
                        e.printStackTrace();
                        Logger.log(AndroidLogger.TYPE_USER, "User Sync failed due to bad payload|" + e.getMessage());
                        return BAD_DATA;
                    } catch (UnfullfilledRequirementsException e) {
                        e.printStackTrace();
                        Logger.log(AndroidLogger.TYPE_ERROR_ASSERTION, "User sync failed oddly, unfulfilled reqs |" + e.getMessage());
                    } catch (IllegalStateException e) {
                        e.printStackTrace();
                        Logger.log(AndroidLogger.TYPE_ERROR_ASSERTION, "User sync failed oddly, ISE |" + e.getMessage());
                    } catch (StorageFullException e) {
                        e.printStackTrace();
                        Logger.log(AndroidLogger.TYPE_ERROR_ASSERTION, "Storage Full during user sync |" + e.getMessage());
                    } 
                } else if(responseCode == 412) {
                    //Our local state is bad. We need to do a full restore.
                    int returnCode = recover(requestor, factory);
                    
                    if(returnCode == PROGRESS_DONE) {
                        //All set! Awesome recovery
                        this.publishProgress(PROGRESS_DONE);
                        return DOWNLOAD_SUCCESS;
                    }
                    
                    else if(returnCode == PROGRESS_RECOVERY_FAIL_SAFE) {
                        //Things didn't go super well, but they might next time!
                        
                        //wipe our login if one happened
                        if(loginNeeded) {
                            CommCareApplication._().releaseUserResourcesAndServices();
                        }
                        this.publishProgress(PROGRESS_DONE);
                        return UNKNOWN_FAILURE;
                    } else if(returnCode == PROGRESS_RECOVERY_FAIL_BAD) {
                        //WELL! That wasn't so good. TODO: Is there anything 
                        //we can do about this?
                        
                        //wipe our login if one happened
                        if(loginNeeded) {
                            CommCareApplication._().releaseUserResourcesAndServices();
                        }
                        this.publishProgress(PROGRESS_DONE);
                        return UNKNOWN_FAILURE;
                    }
                    
                    if(loginNeeded) {
                        CommCareApplication._().releaseUserResourcesAndServices();
                    }
                } else if(responseCode == 500) {
                    if(loginNeeded) {
                        CommCareApplication._().releaseUserResourcesAndServices();
                    }
                    Logger.log(AndroidLogger.TYPE_USER, "500 Server Error|" + username);
                    return SERVER_ERROR;
                }
                
                
            } catch (SocketTimeoutException e) {
                e.printStackTrace();
                Logger.log(AndroidLogger.TYPE_WARNING_NETWORK, "Timed out listening to receive data during sync");
                responseError = CONNECTION_TIMEOUT;
            } catch (ConnectTimeoutException e) {
                e.printStackTrace();
                Logger.log(AndroidLogger.TYPE_WARNING_NETWORK, "Timed out listening to receive data during sync");
                responseError = CONNECTION_TIMEOUT;
            } catch (ClientProtocolException e) {
                e.printStackTrace();
                Logger.log(AndroidLogger.TYPE_WARNING_NETWORK, "Couldn't sync due network error|" + e.getMessage());
            } catch (UnknownHostException e) {
                Logger.log(AndroidLogger.TYPE_WARNING_NETWORK, "Couldn't sync due to bad network");
                responseError = UNREACHABLE_HOST;
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
                Logger.log(AndroidLogger.TYPE_WARNING_NETWORK, "Couldn't sync due to IO Error|" + e.getMessage());
            } catch (SessionUnavailableException sue) {
                // TODO PLM: eventually take out this catch. These should be
                // checked locally
                //TODO: Keys were lost somehow.
                sue.printStackTrace();
            }
            if(loginNeeded) {
                CommCareApplication._().releaseUserResourcesAndServices();
            }
            this.publishProgress(PROGRESS_DONE);
            return responseError;
        } finally {
            CommCareSessionService.sessionAliveLock.unlock();
        }
    }
    
    /**
     * Retrieves the HttpResponse stream and writes it to an initialized safe
     * local cache. Notifies listeners of progress through the download if its
     * size is available.
     *
     * @throws IOException If there is an issue reading or writing the response.
     */
    private BitCache writeResponseToCache(HttpResponse response) throws IOException {
        BitCache cache = null;
        try {
            final long dataSizeGuess = guessDataSize(response);
            
            cache = BitCacheFactory.getCache(c, dataSizeGuess);
            
            cache.initializeCache();
            
            OutputStream cacheOut = cache.getCacheStream();
            InputStream input;
            
            if(DEBUG_LOAD_FROM_LOCAL) {
                input = this.mDebugStream;
            } else {
                input = AndroidHttpClient.getUngzippedContent(response.getEntity());
            }
            
            Log.i("commcare-network", "Starting network read, expected content size: " + dataSizeGuess + "b");
            AndroidStreamUtil.writeFromInputToOutput(new BufferedInputStream(input), cacheOut, new StreamReadObserver() {
                long lastOutput = 0;
                
                /** The notification threshold. **/
                static final int PERCENT_INCREASE_THRESHOLD = 4;
    
                @Override
                public void notifyCurrentCount(long bytesRead) {
                    boolean notify = false;
                    
                    //We always wanna notify when we get our first bytes
                    if(lastOutput == 0) {
                        Log.i("commcare-network", "First"  + bytesRead + " bytes received from network: ");
                        notify = true;
                    }
                    //After, if we don't know how much data to expect, we can't do
                    //anything useful
                    if(dataSizeGuess == -1) {
                        //set this so the first notification up there doesn't keep firing
                        lastOutput = bytesRead;
                        return;
                    }
                    
                    int percentIncrease = (int)(((bytesRead - lastOutput) * 100) / dataSizeGuess);
                    
                    //Now see if we're over the reporting threshold
                    //TODO: Is this actually necessary? In theory this shouldn't 
                    //matter due to android task polling magic?
                    notify = percentIncrease > PERCENT_INCREASE_THRESHOLD; 
                        
                    if(notify) {
                        lastOutput = bytesRead;
                        int totalRead = (int)(((bytesRead) * 100) / dataSizeGuess);
                        publishProgress(PROGRESS_DOWNLOADING, totalRead);
                    }
                }
            });
            
            return cache;
        
        //If something goes wrong while we're reading into the cache
        //we may need to free the storage we reserved.
        } catch (IOException e) {
            if(cache != null) { 
                cache.release();
            }
            throw e;
        }
    }

    /**
     * Get an estimation of how large the provided response is.
     * @return -1 for unknown.
     */
    private long guessDataSize(HttpResponse response) {
        if(DEBUG_LOAD_FROM_LOCAL) {
            try {
                //Note: this is really stupid, but apparently you can't 
                //retrieve the size of Assets due to some bullshit, so
                //this is the closest you get.
                return this.mDebugStream.available();
            } catch (IOException e) {
                return -1;
            }
        }
        if(response.containsHeader("Content-Length")) {
            String length = response.getFirstHeader("Content-Length").getValue();
            try{
                return Long.parseLong(length);
            } catch(Exception e) {
                //Whatever.
            }
        }
        return -1;
    }

    //TODO: This and the normal sync share a ton of code. It's hard to really... figure out the right way to 
    private int recover(HttpRequestGenerator requestor, CommCareTransactionParserFactory factory) {
        this.publishProgress(PROGRESS_RECOVERY_NEEDED);
        
        Logger.log(AndroidLogger.TYPE_USER, "Sync Recovery Triggered");

        
        BitCache cache = null;
        
        //This chunk is the safe field of operations which can all fail in IO in such a way that we can
        //just report back that things didn't work and don't need to attempt any recovery or additional
        //work
        try {

            //Make a new request without all of the flags
            HttpResponse response = requestor.makeCaseFetchRequest(server, false);
            int responseCode = response.getStatusLine().getStatusCode();
            
            //We basically only care about a positive response, here. Anything else would have been caught by the other request.
            if(!(responseCode >= 200 && responseCode < 300)) {
                return PROGRESS_RECOVERY_FAIL_SAFE;
            }
            
            //Grab a cache. The plan is to download the incoming data, wipe (move) the existing db, and then
            //restore fresh from the downloaded file
            cache = writeResponseToCache(response);
                
        } catch(IOException e) {
            e.printStackTrace();
            //Ok, well, we're bailing here, but we didn't make any changes
            Logger.log(AndroidLogger.TYPE_USER, "Sync Recovery Failed due to IOException|" + e.getMessage());
            return PROGRESS_RECOVERY_FAIL_SAFE;
        }
        
        
        this.publishProgress(PROGRESS_RECOVERY_STARTED);
        Logger.log(AndroidLogger.TYPE_USER, "Sync Recovery payload downloaded");

        //Ok. Here's where things get real. We now have a stable copy of the fresh data from the
        //server, so it's "safe" for us to wipe the casedb copy of it.
        
        //CTS: We're not doing this in a super good way right now, need to be way more fault tolerant.
        //this is the temporary implementation of everything past this point
        
        //Wipe storage
        //TODO: move table instead. Should be straightforward with sandboxed db's
        CommCareApplication._().getUserStorage(ACase.STORAGE_KEY, ACase.class).removeAll();
        
        
        String failureReason = "";
        try { 
            //Get new data
            String syncToken = readInput(cache.retrieveCache(), factory);
            updateUserSyncToken(syncToken);
            Logger.log(AndroidLogger.TYPE_USER, "Sync Recovery Succesful");
            return PROGRESS_DONE;
        } catch (InvalidStructureException e) {
            e.printStackTrace();
            failureReason = e.getMessage();
        } catch (XmlPullParserException e) {
            e.printStackTrace();
            failureReason = e.getMessage();
        } catch (UnfullfilledRequirementsException e) {
            e.printStackTrace();
            failureReason = e.getMessage();
        } catch (StorageFullException e) {
            e.printStackTrace();
            failureReason = e.getMessage();
        } 
        
        //These last two aren't a sign that the incoming data is bad, but
        //we still can't recover from them usefully
        catch (SessionUnavailableException e) {
            e.printStackTrace();
            failureReason = e.getMessage();
        } catch (IOException e) {
            e.printStackTrace();
            failureReason = e.getMessage();
        } finally {
            //destroy temp file
            cache.release();
        }
        
        //OK, so we would have returned success by now if things had worked out, which means that instead we got an error
        //while trying to parse everything out. We need to recover from that error here and rollback the changes
        
        //TODO: Roll back changes
        Logger.log(AndroidLogger.TYPE_USER, "Sync recovery failed|" + failureReason);
        return PROGRESS_RECOVERY_FAIL_BAD;
    }

    //Utility method for debugging of people need to dump the response b
    private void dumpCache(BitCache cache) {
        try{
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            AndroidStreamUtil.writeFromInputToOutput(cache.retrieveCache(), baos);
            Log.d(TAG, new String(baos.toByteArray()));
        } catch(IOException e) {
            e.printStackTrace();
        }
    }

    private void updateUserSyncToken(String syncToken) {
        SqlStorage<User> storage = CommCareApplication._().getUserStorage(User.class);
        try {
            User u = storage.getRecordForValue(User.META_USERNAME, username);
            u.setSyncToken(syncToken);
            storage.write(u);
        } catch(NoSuchElementException nsee) {
            //TODO: Something here? Maybe figure out if we downloaded a user from the server and attach the data to it?
        }
    }

    private void purgeCases() {
        long start = System.currentTimeMillis();
        //We need to determine if we're using ownership for purging. For right now, only in sync mode
        Vector<String> owners = new Vector<String>();
        Vector<String> users = new Vector<String>(); 
        for(IStorageIterator<User> userIterator = CommCareApplication._().getUserStorage(User.class).iterate(); userIterator.hasMore();) {
            String id = userIterator.nextRecord().getUniqueId();
            owners.addElement(id);
            users.addElement(id);
        }
        
        //Now add all of the relevant groups
        //TODO: Wow. This is.... kind of megasketch
        for(String userId : users) {
            DataInstance instance = CommCareUtil.loadFixture("user-groups", userId);
            if(instance == null) { continue; }
            EvaluationContext ec = new EvaluationContext(instance);
            for(TreeReference ref : ec.expandReference(XPathReference.getPathExpr("/groups/group/@id").getReference())) {
                AbstractTreeElement<AbstractTreeElement> idelement = ec.resolveReference(ref);
                if(idelement.getValue() != null) {
                    owners.addElement(idelement.getValue().uncast().getString());
                }
            }
        }
            
        SqlStorage<ACase> storage = CommCareApplication._().getUserStorage(ACase.STORAGE_KEY, ACase.class);
        CasePurgeFilter filter = new CasePurgeFilter(storage, owners);
        int removedCases = storage.removeAll(filter).size();
        
        SqlStorage<Ledger> stockStorage = CommCareApplication._().getUserStorage(Ledger.STORAGE_KEY, Ledger.class);
        LedgerPurgeFilter stockFilter = new LedgerPurgeFilter(stockStorage, storage);
        int removedLedgers = stockStorage.removeAll(stockFilter).size();
        
        long taken = System.currentTimeMillis() - start;
        
        Logger.log(AndroidLogger.TYPE_MAINTENANCE, String.format("Purged [%d Case, %d Ledger] records in %dms", removedCases, removedLedgers, taken));
    }

    private String readInput(InputStream stream, CommCareTransactionParserFactory factory) throws InvalidStructureException, IOException, XmlPullParserException, UnfullfilledRequirementsException, SessionUnavailableException{
        DataModelPullParser parser;
        
        factory.initCaseParser();
        factory.initStockParser();
        
        Hashtable<String,String> formNamespaces = new Hashtable<String, String>(); 
        
        for(String xmlns : CommCareApplication._().getCommCarePlatform().getInstalledForms()) {
            Cursor cur = c.getContentResolver().query(CommCareApplication._().getCommCarePlatform().getFormContentUri(xmlns), new String[] {FormsColumns.FORM_FILE_PATH}, null, null, null);
            if(cur.moveToFirst()) {
                String path = cur.getString(cur.getColumnIndex(FormsColumns.FORM_FILE_PATH));
                formNamespaces.put(xmlns, path);
            } else {
                throw new RuntimeException("No form registered for xmlns at content URI: " + CommCareApplication._().getCommCarePlatform().getFormContentUri(xmlns));
            }
            cur.close();
        }
        factory.initFormInstanceParser(formNamespaces);
        
//        SqlIndexedStorageUtility<FormRecord> formRecordStorge = CommCareApplication._().getStorage(FormRecord.STORAGE_KEY, FormRecord.class);
//
//        for(SqlStorageIterator<FormRecord> i = formRecordStorge.iterate(); i.hasNext() ;) {
//            
//        }
        
        //this is _really_ coupled, but we'll tolerate it for now because of the absurd performance gains
        SQLiteDatabase db = CommCareApplication._().getUserDbHandle();
        try {
            db.beginTransaction();
            parser = new DataModelPullParser(stream, factory, this);
            parser.parse();
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        
        
        //Return the sync token ID
        return factory.getSyncToken();
    }
    
    //BEGIN - OTA Listener methods below - Note that most of the methods
    //below weren't really implemented
    
    @Override
    public void onUpdate(int numberCompleted) {
        mCurrentProgress = numberCompleted;
        int miliSecElapsed = (int)(System.currentTimeMillis() - mSyncStartTime);
        
        this.publishProgress(PROGRESS_PROCESSING, mCurrentProgress, mTotalItems, miliSecElapsed);
    }

    @Override
    public void setTotalForms(int totalItemCount) {
        mTotalItems = totalItemCount;
        mCurrentProgress = 0;
        mSyncStartTime = System.currentTimeMillis();
        this.publishProgress(PROGRESS_PROCESSING, mCurrentProgress, mTotalItems, 0);
    }

    @Override
    public void statusUpdate(int statusNumber) {}

    @Override
    public void refreshView() {}

    @Override
    public void getCredentials() {}

    @Override
    public void promptRetry(String msg) {}

    @Override
    public void onSuccess() {}

    @Override
    public void onFailure(String failMessage) {}

}
