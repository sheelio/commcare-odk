package org.commcare.dalvik.dialogs;

/**
 * @author amstone326
 *
 * Any progress dialog associated with a CommCareTask should use
 * this class to implement the dialog. Any class that launches such a task
 * should implement the generateProgressDialog() method of the DialogController
 * interface, and create the dialog in that method. The rest of the dialog's
 * lifecycle is handled by methods of the DialogController interface that are
 * fully implemented in CommCareActivity
 */

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.util.Log;

import org.commcare.dalvik.BuildConfig;

public class CustomProgressDialog extends AlertDialog {
    public static final String TAG = CustomProgressDialog.class.getSimpleName();

    //keys for onSaveInstanceState
    private final static String KEY_TITLE = "title";
    private final static String KEY_MESSAGE = "message";
    private final static String KEY_USING_CB = "using_checkbox";
    private final static String KEY_IS_CHECKED = "is_checked";
    private final static String KEY_CB_TEXT = "checkbox_text";
    private final static String KEY_USING_BUTTON = "using_cancel_buton";
    private final static String KEY_TASK_ID = "task_id";
    private final static String KEY_CANCELABLE = "is_cancelable";
    private final static String KEY_USING_PROGRESS_BAR = "using_progress_bar";
    private final static String KEY_PROGRESS_BAR_PROGRESS = "progress_bar_progress";
    private final static String KEY_PROGRESS_BAR_MAX = "progress_bar_max";

    //id of the task that spawned this dialog, -1 if not associated with a CommCareTask
    private int taskId;

    //for all dialogs
    private String title;
    private String message;
    private boolean isCancelable; //default is false, only set to true if setCancelable() is explicitly called

    //for checkboxes
    private boolean usingCheckbox;
    private boolean isChecked;
    private String checkboxText;

    //for cancel button
    private boolean usingCancelButton;

    //for progress bar
    private boolean usingProgressBar;
    private int progressBarProgress;
    private int progressBarMax;
    private AlertDialog dialog;

    public CustomProgressDialog(Context context) {
        super(context);
    }

    public static CustomProgressDialog newInstance(Activity activity, String title, String message, int taskId) {
        if (BuildConfig.DEBUG) {
            Log.v(TAG, "Current activity for newInstance: " + activity);
        }
        CustomProgressDialog.Builder builder = new Builder(activity, android.R.style.Theme_Material_Light_Dialog);
        builder.setTitle(title)
                .setMessage(message)
                ;
//        CustomProgressDialog frag = new CustomProgressDialog();
//        frag.setTitle(title);
//        frag.setMessage(message);
//        frag.setTaskId(taskId);
//        return frag;
        CustomProgressDialog dialog = CustomProgressDialog.wrap(activity, builder.create());
        dialog.setTaskId(taskId);
        return dialog;
    }

    @Override
    public void show() {
        dialog.show();
    }

    private static CustomProgressDialog wrap(Activity activity, AlertDialog alertDialog) {
        CustomProgressDialog dialog = new CustomProgressDialog(activity);
        dialog.dialog = alertDialog;
        return dialog;
    }

    public void addCheckbox(String text, boolean isChecked) {
        this.usingCheckbox = true;
        this.checkboxText = text;
        this.isChecked = isChecked;
    }

    public void addCancelButton() {
        usingCancelButton = true;
    }

    public void setCancelable() {
        this.isCancelable = true;
    }

    public void addProgressBar() {
        this.usingProgressBar = true;
        this.progressBarProgress = 0;
        this.progressBarMax = 0;
    }

    private void setTaskId(int id) {
        this.taskId = id;
    }

    public int getTaskId() {
        return taskId;
    }

    private void setTitle(String s) {
        this.title = s;
    }

    private void setMessage(String s) {
        this.message = s;
    }

    public boolean isChecked() {
        return isChecked;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restoreFields(savedInstanceState);
    }

    private void restoreFields(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            this.title = savedInstanceState.getString(KEY_TITLE);
            this.message = savedInstanceState.getString(KEY_MESSAGE);
            this.usingCheckbox = savedInstanceState.getBoolean(KEY_USING_CB);
            this.isChecked = savedInstanceState.getBoolean(KEY_IS_CHECKED);
            this.checkboxText = savedInstanceState.getString(KEY_CB_TEXT);
            this.usingCancelButton = savedInstanceState.getBoolean(KEY_USING_BUTTON);
            this.taskId = savedInstanceState.getInt(KEY_TASK_ID);
            this.isCancelable = savedInstanceState.getBoolean(KEY_CANCELABLE);
            this.usingProgressBar = savedInstanceState.getBoolean(KEY_USING_PROGRESS_BAR);
            this.progressBarProgress = savedInstanceState.getInt(KEY_PROGRESS_BAR_PROGRESS);
            this.progressBarMax = savedInstanceState.getInt(KEY_PROGRESS_BAR_MAX);
        }
    }

//    @Override
//    public void onDestroyView() {
//        if (getDialog() != null && getRetainInstance()) {
//            getDialog().setDismissMessage(null);
//        }
//        super.onDestroyView();
//    }


    /* Creates the dialog that will reside within the fragment */
//    @Override
//    public Dialog onCreateDialog(Bundle savedInstanceState) {
//        restoreFields(savedInstanceState);
//        ContextThemeWrapper wrapper = new ContextThemeWrapper(getActivity(), R.style.DialogBaseTheme);
//        AlertDialog.Builder builder = new AlertDialog.Builder(wrapper);
//        builder.setTitle(title);
//        builder.setCancelable(isCancelable);
//        View view = LayoutInflater.from(wrapper).inflate(R.layout.fragment_progress_dialog, null);
//
//        TextView tv = (TextView) view.findViewById(R.id.progress_dialog_message);
//        tv.setText(message);
//
//         //All logic for if this dialog uses a checkbox
//        if (usingCheckbox) {
//
//            CheckBox cb = (CheckBox) view.findViewById(R.id.progress_dialog_checkbox);
//            cb.setVisibility(View.VISIBLE);
//            cb.setText(checkboxText);
//            cb.setOnClickListener(new OnClickListener() {
//
//                @Override
//                public void onClick(View v) {
//                    isChecked = ((CheckBox)v).isChecked();
//                }
//
//            });
//            if (isChecked) {
//                cb.toggle();
//            }
//        }
//
//        //Logic for cancel button
//        if (usingCancelButton) {
//            Button b = (Button) view.findViewById(R.id.dialog_cancel_button);
//            b.setOnClickListener(new OnClickListener() {
//
//                @Override
//                public void onClick(View v) {
//                    ((CommCareActivity)getActivity()).cancelCurrentTask();
//                }
//
//            });
//            b.setVisibility(View.VISIBLE);
//        }
//
//        if (usingProgressBar) {
//            ProgressBar bar = (ProgressBar) view.findViewById(R.id.progress_bar_horizontal);
//            bar.setProgressDrawable(getResources().getDrawable(R.drawable.progressbar));
//            bar.setProgress(progressBarProgress);
//            bar.setMax(progressBarMax);
//            bar.setVisibility(View.VISIBLE);
//
//            // If there's a determinate progress bar, hide the spinning indicator
//            view.findViewById(R.id.progress_bar).setVisibility(View.GONE);
//        }
//
//        builder.setView(view);
//        Dialog d = builder.create();
//        d.setCanceledOnTouchOutside(isCancelable);
//        return d;
//    }

    public void updateMessage(String text) {
        this.message = text;
//        AlertDialog pd = (AlertDialog) getDialog();
//        if (pd != null) {
//            TextView tv = (TextView) pd.findViewById(R.id.progress_dialog_message);
//            tv.setText(this.message);
//        }
    }

//    @Override
//    public void onSaveInstanceState(Bundle outState) {
//        super.onSaveInstanceState(outState);
//        outState.putString(KEY_TITLE, this.title);
//        outState.putString(KEY_MESSAGE, this.message);
//        outState.putBoolean(KEY_USING_CB, this.usingCheckbox);
//        outState.putBoolean(KEY_IS_CHECKED, this.isChecked);
//        outState.putString(KEY_CB_TEXT, this.checkboxText);
//        outState.putBoolean(KEY_USING_BUTTON, this.usingCancelButton);
//        outState.putInt(KEY_TASK_ID, this.taskId);
//        outState.putBoolean(KEY_CANCELABLE, this.isCancelable);
//        outState.putBoolean(KEY_USING_PROGRESS_BAR, this.usingProgressBar);
//        outState.putInt(KEY_PROGRESS_BAR_PROGRESS, this.progressBarProgress);
//        outState.putInt(KEY_PROGRESS_BAR_MAX, this.progressBarMax);
//    }

    public void updateProgressBar(int progress, int max) {
        this.progressBarProgress = progress;
        this.progressBarMax = max;
//        Dialog dialog = getDialog();
//        if (dialog != null) {
//            ProgressBar bar = (ProgressBar) dialog.findViewById(R.id.progress_bar_horizontal);
//            bar.setProgress(progress);
//            bar.setMax(max);
//        }
    }

}
