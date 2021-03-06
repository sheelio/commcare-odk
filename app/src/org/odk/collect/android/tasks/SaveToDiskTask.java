package org.odk.collect.android.tasks;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;

import org.commcare.dalvik.odk.provider.FormsProviderAPI.FormsColumns;
import org.commcare.dalvik.odk.provider.InstanceProviderAPI;
import org.commcare.dalvik.odk.provider.InstanceProviderAPI.InstanceColumns;
import org.javarosa.core.io.StreamsUtil;
import org.javarosa.core.model.FormDef;
import org.javarosa.core.model.FormIndex;
import org.javarosa.core.model.instance.FormInstance;
import org.javarosa.core.services.transport.payload.ByteArrayPayload;
import org.javarosa.form.api.FormEntryController;
import org.javarosa.model.xform.XFormSerializingVisitor;
import org.odk.collect.android.activities.FormEntryActivity;
import org.odk.collect.android.listeners.FormSavedListener;
import org.odk.collect.android.logic.FormController;
import org.odk.collect.android.utilities.EncryptionUtils;
import org.odk.collect.android.utilities.EncryptionUtils.EncryptedFormInformation;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;

/**
 * Background task for loading a form.
 * 
 * @author Carl Hartung (carlhartung@gmail.com)
 * @author Yaw Anokwa (yanokwa@gmail.com)
 */
public class SaveToDiskTask extends AsyncTask<Void, String, Integer> {
    private final static String t = "SaveToDiskTask";

    // callback to run upon saving
    private FormSavedListener mSavedListener;
    private Boolean mSave;
    private Boolean mMarkCompleted;
    // URI to the thing we are saving
    private Uri mUri;
    // The name of the form we are saving
    private String mInstanceName;
    private Context context;
    // URI to the table we are saving to
    private Uri instanceContentUri;

    // Should this save task tell its save-complete callback to run without any
    // GUI calls?
    private boolean headless;
    
    SecretKeySpec symetricKey;

    public static final int SAVED = 500;
    public static final int SAVE_ERROR = 501;
    public static final int VALIDATE_ERROR = 502;
    public static final int VALIDATED = 503;
    public static final int SAVED_AND_EXIT = 504;


    public SaveToDiskTask(Uri mUri, Boolean saveAndExit, Boolean markCompleted, String updatedName, Context context, Uri instanceContentUri, SecretKeySpec symetricKey, boolean headless) {
        this.mUri = mUri;
        mSave = saveAndExit;
        mMarkCompleted = markCompleted;
        mInstanceName = updatedName;
        this.context = context;
        this.instanceContentUri = instanceContentUri;
        this.symetricKey = symetricKey;
        this.headless = headless;
    }


    /**
     * Initialize {@link FormEntryController} with {@link FormDef} from binary or from XML. If given
     * an instance, it will be used to fill the {@link FormDef}.
     */
    @Override
    protected Integer doInBackground(Void... nothing) {

        // validation failed, pass specific failure
        int validateStatus = validateAnswers(mMarkCompleted);
        if (validateStatus != VALIDATED) {
            return validateStatus;
        }

        FormEntryActivity.mFormController.postProcessInstance();

        if (exportData(mMarkCompleted)) {
            return mSave ? SAVED_AND_EXIT : SAVED;
        }

        return SAVE_ERROR;
    }

    /**
     * Update or create a new entry in the form table for the
     */
    private void updateInstanceDatabase(boolean incomplete, boolean canEditAfterCompleted) {
        ContentValues values = new ContentValues();
        if (mInstanceName != null) {
            values.put(InstanceColumns.DISPLAY_NAME, mInstanceName);
        }

        if (incomplete || !mMarkCompleted) {
            values.put(InstanceColumns.STATUS, InstanceProviderAPI.STATUS_INCOMPLETE);
        } else {
            values.put(InstanceColumns.STATUS, InstanceProviderAPI.STATUS_COMPLETE);
        }
        // update this whether or not the status is complete.
        values.put(InstanceColumns.CAN_EDIT_WHEN_COMPLETE, Boolean.toString(canEditAfterCompleted));

        // Insert or update the form instance into the database.
        if (context.getContentResolver().getType(mUri) == InstanceColumns.CONTENT_ITEM_TYPE) {
            // Started with a concrete instance (e.i. by editing an existing
            // form), so just update it.
            context.getContentResolver().update(mUri, values, null, null);
        } else if (context.getContentResolver().getType(mUri) == FormsColumns.CONTENT_ITEM_TYPE) {
            // Started with an empty form or possibly a manually saved form.
            // Try updating, and create a new instance if that fails.
            String[] whereArgs = {FormEntryActivity.mInstancePath};
            int rowsUpdated = context.getContentResolver().update(instanceContentUri, values,
                    InstanceColumns.INSTANCE_FILE_PATH + "=?", whereArgs);
            if (rowsUpdated == 0) {
                // Form instance didn't exist in the table, so create it.
                Log.e(t, "No instance found, creating");
                Cursor c = null;
                try {
                    // grab the first entry in the instance table for the form
                    c = context.getContentResolver().query(mUri, null, null, null, null);
                    c.moveToFirst();
                    // copy data out of that entry, into the entry we are creating
                    values.put(InstanceColumns.JR_FORM_ID,
                            c.getString(c.getColumnIndex(FormsColumns.JR_FORM_ID)));
                    values.put(InstanceColumns.SUBMISSION_URI,
                            c.getString(c.getColumnIndex(FormsColumns.SUBMISSION_URI)));

                    if (mInstanceName == null) {
                        values.put(InstanceColumns.DISPLAY_NAME,
                                c.getString(c.getColumnIndex(FormsColumns.DISPLAY_NAME)));
                    }
                } finally {
                    if (c != null) {
                        c.close();
                    }
                }
                values.put(InstanceColumns.INSTANCE_FILE_PATH, FormEntryActivity.mInstancePath);
                mUri = context.getContentResolver().insert(instanceContentUri, values);
            } else if (rowsUpdated == 1) {
                Log.i(t, "Instance already exists, updating");
            } else{
                Log.w(t, "Updated more than one entry, that's not good");
            }
        }
    }

    /**
     * Write's the data to the sdcard, and updates the instances content provider.
     * In theory we don't have to write to disk, and this is where you'd add
     * other methods.
     * @return was writing of data successful?
     */
    private boolean exportData(boolean markCompleted) {
        ByteArrayPayload payload;
        try {

            // assume no binary data inside the model.
            FormInstance datamodel = FormEntryActivity.mFormController.getInstance();
            XFormSerializingVisitor serializer = new XFormSerializingVisitor(markCompleted);
            payload = (ByteArrayPayload) serializer.createSerializedPayload(datamodel);

            // write out xml
            exportXmlFile(payload, createFileOutputStream(FormEntryActivity.mInstancePath));

        } catch (IOException e) {
            Log.e(t, "Error creating serialized payload");
            e.printStackTrace();
            return false;
        }

        // update the mUri. We've saved the reloadable instance, so update status...

        try {
            updateInstanceDatabase(true, true);
        } catch (SQLException e) {
            Log.e(t, "Error creating database entries for form.");
            e.printStackTrace();
            return false;
        }
        
        if ( markCompleted ) {
            // now see if it is to be finalized and perhaps update everything...
            boolean canEditAfterCompleted = FormEntryActivity.mFormController.isSubmissionEntireForm();
            boolean isEncrypted = false;
            
            // build a submission.xml to hold the data being submitted 
            // and (if appropriate) encrypt the files on the side

            // pay attention to the ref attribute of the submission profile...
            try {
                payload = FormEntryActivity.mFormController.getSubmissionXml();
            } catch (IOException e) {
                Log.e(t, "Error creating serialized payload");
                e.printStackTrace();
                return false;
            }

            File instanceXml = new File(FormEntryActivity.mInstancePath);
            File submissionXml = new File(instanceXml.getParentFile(), "submission.xml");
            // write out submission.xml -- the data to actually submit to aggregate
            try {
                exportXmlFile(payload, createFileOutputStream(submissionXml.getAbsolutePath()));
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                throw new RuntimeException("Something is blocking acesss to the file at " + submissionXml.getAbsolutePath());
            }
            
            // see if the form is encrypted and we can encrypt it...
            EncryptedFormInformation formInfo = EncryptionUtils.getEncryptedFormInformation(mUri, FormEntryActivity.mFormController.getSubmissionMetadata(), context, instanceContentUri);
            if ( formInfo != null ) {
                // if we are encrypting, the form cannot be reopened afterward
                canEditAfterCompleted = false;
                // and encrypt the submission (this is a one-way operation)...
                if ( !EncryptionUtils.generateEncryptedSubmission(instanceXml, submissionXml, formInfo) ) {
                    return false;
                }
                isEncrypted = true;
            }
            
            // At this point, we have:
            // 1. the saved original instanceXml, 
            // 2. all the plaintext attachments
            // 2. the submission.xml that is the completed xml (whether encrypting or not)
            // 3. all the encrypted attachments if encrypting (isEncrypted = true).
            //
            // NEXT:
            // 1. Update the instance database (with status complete).
            // 2. Overwrite the instanceXml with the submission.xml 
            //    and remove the plaintext attachments if encrypting
            
            try {
                updateInstanceDatabase(false, canEditAfterCompleted);
            } catch (SQLException e) {
                Log.e(t, "Error creating database entries for form.");
                e.printStackTrace();
                return false;
            }

            if (  !canEditAfterCompleted ) {
                // AT THIS POINT, there is no going back.  We are committed
                // to returning "success" (true) whether or not we can 
                // rename "submission.xml" to instanceXml and whether or 
                // not we can delete the plaintext media files.
                //
                // Handle the fall-out for a failed "submission.xml" rename
                // in the InstanceUploader task.  Leftover plaintext media
                // files are handled during form deletion.
    
                // delete the restore Xml file.
                if ( !instanceXml.delete() ) {
                    Log.e(t, "Error deleting " + instanceXml.getAbsolutePath() 
                            + " prior to renaming submission.xml");
                    return true;
                }
    
                // rename the submission.xml to be the instanceXml
                if ( !submissionXml.renameTo(instanceXml) ) {
                    Log.e(t, "Error renaming submission.xml to " + instanceXml.getAbsolutePath());
                    return true;
                }
                
                // if encrypted, delete all plaintext files
                // (anything not named instanceXml or anything not ending in .enc)
                if ( isEncrypted ) {
                    if ( !EncryptionUtils.deletePlaintextFiles(instanceXml) ) {
                        Log.e(t, "Error deleting plaintext files for " + instanceXml.getAbsolutePath());
                    }
                }
            }
        }
        return true;
    }
    
    public OutputStream createFileOutputStream(String path) throws FileNotFoundException {
        return createFileOutputStream(new File(path));
    }
    
    private OutputStream createFileOutputStream(File path) throws FileNotFoundException {
        FileOutputStream fos = new FileOutputStream(path);
        if(symetricKey == null) {
            return fos;
        } else {
            try {
                Cipher cipher = Cipher.getInstance("AES");
                cipher.init(Cipher.ENCRYPT_MODE, symetricKey);
                return new CipherOutputStream(fos, cipher);
                
                //All of these exceptions imply a bad platform and should be irrecoverable (Don't ever
                //write out data if the key isn't good, or the crypto isn't available)
            } catch (InvalidKeyException e) {
                e.printStackTrace();
                throw new RuntimeException(e.getMessage());
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
                throw new RuntimeException(e.getMessage());
            } catch (NoSuchPaddingException e) {
                e.printStackTrace();
                throw new RuntimeException(e.getMessage());
            }
        }
    }


    /**
     * This method actually writes the xml to disk.
     */
    private boolean exportXmlFile(ByteArrayPayload payload, OutputStream output) {
        // create data stream
        InputStream is = payload.getPayloadStream();
        try {
            StreamsUtil.writeFromInputToOutput(is, output);
            output.close();
            return true;
        } catch (IOException e) {
            Log.e(t, "Error reading from payload data stream");
            e.printStackTrace();
            return false;
        }
    }


    @Override
    protected void onPostExecute(Integer result) {
        synchronized (this) {
            if (mSavedListener != null)
                mSavedListener.savingComplete(result, headless);
        }
    }


    public void setFormSavedListener(FormSavedListener fsl) {
        synchronized (this) {
            mSavedListener = fsl;
        }
    }


    /**
     * Goes through the entire form to make sure all entered answers comply with their constraints.
     * Constraints are ignored on 'jump to', so answers can be outside of constraints. We don't
     * allow saving to disk, though, until all answers conform to their constraints/requirements.
     */
    private int validateAnswers(Boolean markCompleted) {
        FormIndex i = FormEntryActivity.mFormController.getFormIndex();
        FormEntryActivity.mFormController.jumpToIndex(FormIndex.createBeginningOfFormIndex());

        int event;
        while ((event =
            FormEntryActivity.mFormController.stepToNextEvent(FormController.STEP_INTO_GROUP)) != FormEntryController.EVENT_END_OF_FORM) {
            if (event != FormEntryController.EVENT_QUESTION) {
                continue;
            } else {
                int saveStatus =
                    FormEntryActivity.mFormController
                            .answerQuestion(FormEntryActivity.mFormController.getQuestionPrompt()
                                    .getAnswerValue());
                if (markCompleted && saveStatus != FormEntryController.ANSWER_OK) {
                    return saveStatus;
                }
            }
        }

        FormEntryActivity.mFormController.jumpToIndex(i);
        return VALIDATED;
    }
}
