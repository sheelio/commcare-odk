package org.commcare.android.framework;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.text.Spannable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Pair;
import android.view.Display;
import android.view.GestureDetector;
import android.view.GestureDetector.OnGestureListener;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import org.commcare.android.database.user.models.ACase;
import org.commcare.android.javarosa.AndroidLogger;
import org.commcare.android.tasks.templates.CommCareTask;
import org.commcare.android.tasks.templates.CommCareTaskConnector;
import org.commcare.android.util.AndroidUtil;
import org.commcare.android.util.MarkupUtil;
import org.commcare.android.util.SessionStateUninitException;
import org.commcare.android.util.StringUtils;
import org.commcare.android.view.ViewUtil;
import org.commcare.dalvik.BuildConfig;
import org.commcare.dalvik.application.CommCareApp;
import org.commcare.dalvik.application.CommCareApplication;
import org.commcare.dalvik.dialogs.CustomProgressDialog;
import org.commcare.dalvik.dialogs.DialogController;
import org.commcare.dalvik.preferences.CommCarePreferences;
import org.commcare.suite.model.Detail;
import org.commcare.suite.model.StackFrameStep;
import org.commcare.util.SessionFrame;
import org.javarosa.core.model.instance.TreeReference;
import org.javarosa.core.services.Logger;
import org.javarosa.core.services.locale.Localization;
import org.javarosa.core.util.NoLocalizedTextException;
import org.odk.collect.android.views.media.AudioController;

import java.lang.reflect.Field;

/**
 * Base class for CommCareActivities to simplify
 * common localization and workflow tasks
 *
 * @author ctsims
 */
public abstract class CommCareActivity<R> extends FragmentActivity
        implements CommCareTaskConnector<R>, DialogController, OnGestureListener {
    private static final String TAG = CommCareActivity.class.getSimpleName();

    private final static String KEY_DIALOG_FRAG = "dialog_fragment";

    private boolean mBannerOverriden = false;

    StateFragment stateHolder;

    //fields for implementing task transitions for CommCareTaskConnector
    private boolean inTaskTransition;

    /**
     * Used to indicate that the (progress) dialog associated with a task
     * should be dismissed because the task has completed or been canceled.
     */
    private boolean shouldDismissDialog = true;

    private GestureDetector mGestureDetector;

    public static final String KEY_LAST_QUERY_STRING = "LAST_QUERY_STRING";
    protected String lastQueryString;

    /**
     * Activity has been put in the background. Flag prevents dialogs
     * from being shown while activity isn't active.
     */
    private boolean activityPaused;

    /**
     * Store the id of a task progress dialog so it can be disabled/enabled
     * on activity pause/resume.
     */
    private int dialogId = -1;

    @Override
    @TargetApi(14)
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FragmentManager fm = this.getSupportFragmentManager();

        stateHolder = (StateFragment) fm.findFragmentByTag("state");

        // stateHolder and its previous state aren't null if the activity is
        // being created due to an orientation change.
        if (stateHolder == null) {
            stateHolder = new StateFragment();
            fm.beginTransaction().add(stateHolder, "state").commit();
            // entering new activity, not just rotating one, so release old
            // media
            AudioController.INSTANCE.releaseCurrentMediaEntity();
        }

        if (this.getClass().isAnnotationPresent(ManagedUi.class)) {
            this.setContentView(this.getClass().getAnnotation(ManagedUi.class).value());
            loadFields(true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            getActionBar().setDisplayShowCustomEnabled(true);

            // Add breadcrumb bar
            BreadcrumbBarFragment bar = (BreadcrumbBarFragment) fm.findFragmentByTag("breadcrumbs");

            // If the state holder is null, create a new one for this activity
            if (bar == null) {
                bar = new BreadcrumbBarFragment();
                fm.beginTransaction().add(bar, "breadcrumbs").commit();
            }
        }

        mGestureDetector = new GestureDetector(this, this);
    }

    protected void restoreLastQueryString(String key) {
        SharedPreferences settings = getSharedPreferences(CommCarePreferences.ACTIONBAR_PREFS, 0);
        lastQueryString = settings.getString(key, null);
        if (BuildConfig.DEBUG) {
            Log.v(TAG, "Recovered lastQueryString: (" + lastQueryString + ")");
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                this.onBackPressed();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /**
     * @param restoreOldFields Use the fields already on screen? For refreshing
     *                         fields when the default language changes due to
     *                         the app being changed on the home screen
     *                         multiple app drop-down menu.
     */
    protected void loadFields(boolean restoreOldFields) {
        CommCareActivity oldActivity = stateHolder.getPreviousState();
        Class c = this.getClass();
        for (Field f : c.getDeclaredFields()) {
            if (f.isAnnotationPresent(UiElement.class)) {
                UiElement element = f.getAnnotation(UiElement.class);
                try {
                    f.setAccessible(true);

                    try {
                        View v = this.findViewById(element.value());
                        f.set(this, v);

                        if (oldActivity != null && restoreOldFields) {
                            View oldView = (View) f.get(oldActivity);
                            if (oldView != null) {
                                if (v instanceof TextView) {
                                    ((TextView) v).setText(((TextView) oldView).getText());
                                }
                                if (v == null) {
                                    Log.d("loadFields", "NullPointerException when trying to find view with id: " +
                                            +element.value() + " (" + getResources().getResourceEntryName(element.value()) + ") "
                                            + " oldView is " + oldView + " for activity " + oldActivity +
                                            ", element is: " + f + " (" + f.getName() + ")");
                                }
                                v.setVisibility(oldView.getVisibility());
                                v.setEnabled(oldView.isEnabled());
                                continue;
                            }
                        }

                        String localeString = element.locale();
                        if (!"".equals(localeString)) {
                            if (v instanceof EditText) {
                                ((EditText) v).setHint(Localization.get(localeString));
                            } else if (v instanceof TextView) {
                                ((TextView) v).setText(Localization.get(localeString));
                            } else {
                                throw new RuntimeException("Can't set the text for a " + v.getClass().getName() + " View!");
                            }
                        }
                    } catch (IllegalArgumentException e) {
                        e.printStackTrace();
                        throw new RuntimeException("Bad Object type for field " + f.getName());
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException("Couldn't access the activity field for some reason");
                    }
                } finally {
                    f.setAccessible(false);
                }
            }
        }
    }

    protected CommCareActivity getDestroyedActivityState() {
        return stateHolder.getPreviousState();
    }

    protected boolean isTopNavEnabled() {
        return false;
    }

    @Override
    @TargetApi(11)
    protected void onResume() {
        super.onResume();

        activityPaused = false;

        if (dialogId > -1) {
            startBlockingForTask(dialogId);
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
            // In honeycomb and above the fragment takes care of this
            this.setTitle(getTitle(this, getActivityTitle()));
        }

        AudioController.INSTANCE.playPreviousAudio();
    }

    protected View getBannerHost() {
        return this.findViewById(android.R.id.content);
    }

    protected void updateCommCareBanner() {
        View hostView = getBannerHost();
        if (hostView == null) {
            return;
        }
        ImageView topBannerImageView = (ImageView) hostView.findViewById(org.commcare.dalvik.R.id.main_top_banner);
        if (topBannerImageView == null) {
            return;
        }
        CommCareApp app = CommCareApplication._().getCurrentApp();
        if (app == null) {
            return;
        }

        boolean resetBanner = mBannerOverriden;

        Display display = ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        int screenHeight = display.getHeight();

        int maxBannerHeight = screenHeight / 4;

        // Override default CommCare banner if requested
        String customBannerURI = app.getAppPreferences().getString(CommCarePreferences.BRAND_BANNER_HOME, "");
        if (!"".equals(customBannerURI)) {
            Bitmap bitmap = ViewUtil.inflateDisplayImage(this, customBannerURI);
            if (bitmap != null) {
                if (topBannerImageView != null) {
                    topBannerImageView.setMaxHeight(maxBannerHeight);
                    topBannerImageView.setImageBitmap(bitmap);
                    mBannerOverriden = true;
                    resetBanner = false;
                } else {
                    Log.i(TAG, "Coudln't load custom banner at: " + customBannerURI);
                }
            }
        }

        if (resetBanner) {
            topBannerImageView.setImageResource(org.commcare.dalvik.R.drawable.commcare_logo);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        activityPaused = true;
        AudioController.INSTANCE.systemInducedPause();
    }

    @Override
    public <A, B, C> void connectTask(CommCareTask<A, B, C, R> task) {
        stateHolder.connectTask(task);

        //If we've left an old dialog showing during the task transition and it was from the same task
        //as the one that is starting, don't dismiss it
        CustomProgressDialog currDialog = getCurrentDialog();
        if (currDialog != null && currDialog.getTaskId() == task.getTaskId()) {
            shouldDismissDialog = false;
        }
    }


    /**
     * @return wakelock level for an activity with a running task attached to
     * it; defaults to not using wakelocks.
     */
    protected int getWakeLockLevel() {
        return CommCareTask.DONT_WAKELOCK;
    }
    
    /*
     * Override these to control the UI for your task
     */

    @Override
    public void startBlockingForTask(int id) {
        dialogId = id;

        if (activityPaused) {
            // don't show the dialog if the activity is in the background
            return;
        }

        // attempt to dismiss the dialog from the last task before showing this
        // one
        attemptDismissDialog();

        // ONLY if shouldDismissDialog = true, i.e. if we chose to dismiss the
        // last dialog during transition, show a new one
        if (id >= 0 && shouldDismissDialog) {
            this.showProgressDialog(id);
        }
    }

    @Override
    public void stopBlockingForTask(int id) {
        dialogId = -1;
        if (id >= 0) {
            if (inTaskTransition) {
                shouldDismissDialog = true;
            } else {
                dismissProgressDialog();
            }
        }

        stateHolder.releaseWakeLock();
    }

    @Override
    public R getReceiver() {
        return (R) this;
    }

    @Override
    public void startTaskTransition() {
        inTaskTransition = true;
    }

    @Override
    public void stopTaskTransition() {
        inTaskTransition = false;
        attemptDismissDialog();
        //reset shouldDismissDialog to true after this transition cycle is over
        shouldDismissDialog = true;
    }

    //if shouldDismiss flag has not been set to false in the course of a task transition,
    //then dismiss the dialog
    void attemptDismissDialog() {
        if (shouldDismissDialog) {
            dismissProgressDialog();
        }
    }

    /**
     * Handle an error in task execution.
     */
    protected void taskError(Exception e) {
        //TODO: For forms with good error reporting, integrate that
        Toast.makeText(this, Localization.get("activity.task.error.generic", new String[]{e.getMessage()}), Toast.LENGTH_LONG).show();
        Logger.log(AndroidLogger.TYPE_ERROR_WORKFLOW, e.getMessage());
    }

    /**
     * Display exception details as a pop-up to the user.
     *
     * @param e Exception to handle
     */
    protected void displayException(Exception e) {
        String mErrorMessage = e.getMessage();
        AlertDialog mAlertDialog = new AlertDialog.Builder(this).create();
        mAlertDialog.setIcon(android.R.drawable.ic_dialog_info);
        mAlertDialog.setTitle(Localization.get("notification.case.predicate.title"));
        mAlertDialog.setMessage(Localization.get("notification.case.predicate.action", new String[]{mErrorMessage}));
        DialogInterface.OnClickListener errorListener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int i) {
                switch (i) {
                    case DialogInterface.BUTTON1:
                        finish();
                        break;
                }
            }
        };
        mAlertDialog.setCancelable(false);
        mAlertDialog.setButton(Localization.get("dialog.ok"), errorListener);
        mAlertDialog.show();
    }

    @Override
    public void taskCancelled(int id) {

    }

    public void cancelCurrentTask() {
        stateHolder.cancelTask();
    }

    protected void saveLastQueryString(String key) {
        SharedPreferences settings = getSharedPreferences(CommCarePreferences.ACTIONBAR_PREFS, 0);
        SharedPreferences.Editor editor = settings.edit();
        editor.putString(key, lastQueryString);
        editor.commit();

        if (BuildConfig.DEBUG) {
            Log.v(TAG, "Saving lastQueryString: (" + lastQueryString + ") in file: " + CommCarePreferences.ACTIONBAR_PREFS);
        }
    }

    //Graphical stuff below, needs to get modularized

    public void transplantStyle(TextView target, int resource) {
        //get styles from here
        TextView tv = (TextView) View.inflate(this, resource, null);
        int[] padding = {target.getPaddingLeft(), target.getPaddingTop(), target.getPaddingRight(), target.getPaddingBottom()};

        target.setTextColor(tv.getTextColors().getDefaultColor());
        target.setTypeface(tv.getTypeface());
        target.setBackgroundDrawable(tv.getBackground());
        target.setPadding(padding[0], padding[1], padding[2], padding[3]);
    }

    /**
     * The right-hand side of the title associated with this activity.
     * <p/>
     * This will update dynamically as the activity loads/updates, but if
     * it will ever have a value it must return a blank string when one
     * isn't available.
     */
    public String getActivityTitle() {
        return null;
    }

    public static String getTopLevelTitleName(Context c) {
        try {
            return Localization.get("app.display.name");
        } catch (NoLocalizedTextException nlte) {
            return c.getString(org.commcare.dalvik.R.string.title_bar_name);
        }
    }

    public static String getTitle(Context c, String local) {
        String topLevel = getTopLevelTitleName(c);

        String[] stepTitles = new String[0];
        try {
            stepTitles = CommCareApplication._().getCurrentSession().getHeaderTitles();

            //See if we can insert any case hacks
            int i = 0;
            for (StackFrameStep step : CommCareApplication._().getCurrentSession().getFrame().getSteps()) {
                try {
                    if (SessionFrame.STATE_DATUM_VAL.equals(step.getType())) {
                        //Haaack
                        if (step.getId() != null && step.getId().contains("case_id")) {
                            ACase foundCase = CommCareApplication._().getUserStorage(ACase.STORAGE_KEY, ACase.class).getRecordForValue(ACase.INDEX_CASE_ID, step.getValue());
                            stepTitles[i] = Localization.get("title.datum.wrapper", new String[]{foundCase.getName()});
                        }
                    }
                } catch (Exception e) {
                    //TODO: Your error handling is bad and you should feel bad
                }
                ++i;
            }
        } catch (SessionStateUninitException e) {

        }

        StringBuilder titleBuf = new StringBuilder(topLevel);
        for (String title : stepTitles) {
            if (title != null) {
                titleBuf.append(" > ").append(title);
            }
        }

        if (local != null) {
            titleBuf.append(" > ").append(local);
        }
        return titleBuf.toString();
    }

    public boolean isNetworkNotConnected() {
        return !AndroidUtil.isNetworkAvailable(this);
    }

    protected void createErrorDialog(String errorMsg, boolean shouldExit) {
        createErrorDialog(this, errorMsg, shouldExit);
    }

    /**
     * Pop up a semi-friendly error dialog rather than crashing outright.
     *
     * @param activity   Activity to which to attach the dialog.
     * @param shouldExit If true, cancel activity when user exits dialog.
     */
    public static void createErrorDialog(final Activity activity, String errorMsg, final boolean shouldExit) {
        AlertDialog dialog = new AlertDialog.Builder(activity).create();
        dialog.setIcon(android.R.drawable.ic_dialog_info);
        dialog.setTitle(StringUtils.getStringRobust(activity, org.commcare.dalvik.R.string.error_occured));
        dialog.setMessage(errorMsg);
        DialogInterface.OnClickListener errorListener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int i) {
                switch (i) {
                    case DialogInterface.BUTTON_POSITIVE:
                        if (shouldExit) {
                            activity.setResult(RESULT_CANCELED);
                            activity.finish();
                        }
                        break;
                }
            }
        };
        dialog.setCancelable(false);
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, StringUtils.getStringSpannableRobust(activity, org.commcare.dalvik.R.string.ok), errorListener);
        dialog.show();
    }

    /**
     * All methods for implementation of DialogController *
     */


    @Override
    public void updateProgress(String updateText, int taskId) {
        CustomProgressDialog mProgressDialog = getCurrentDialog();
        if (mProgressDialog != null) {
            if (mProgressDialog.getTaskId() == taskId) {
                mProgressDialog.updateMessage(updateText);
            } else {
                Logger.log(AndroidLogger.TYPE_ERROR_ASSERTION,
                        "Attempting to update a progress dialog whose taskId does not match the"
                                + "task for which the update message was intended.");
            }
        }
    }

    @Override
    public void updateProgressBar(int progress, int max, int taskId) {
        CustomProgressDialog mProgressDialog = getCurrentDialog();
        if (mProgressDialog != null) {
            if (mProgressDialog.getTaskId() == taskId) {
                mProgressDialog.updateProgressBar(progress, max);
            } else {
                Logger.log(AndroidLogger.TYPE_ERROR_ASSERTION,
                        "Attempting to update a progress dialog whose taskId does not match the"
                                + "task for which the update message was intended.");
            }
        }
    }

    @Override
    public void showProgressDialog(int taskId) {
        CustomProgressDialog dialog = generateProgressDialog(taskId);
        if (dialog != null) {
            dialog.show(getSupportFragmentManager(), KEY_DIALOG_FRAG);
        }
    }

    @Override
    public CustomProgressDialog getCurrentDialog() {
        return (CustomProgressDialog) getSupportFragmentManager().
                findFragmentByTag(KEY_DIALOG_FRAG);
    }

    @Override
    public void dismissProgressDialog() {
        CustomProgressDialog mProgressDialog = getCurrentDialog();
        if (mProgressDialog != null && mProgressDialog.isAdded()) {
            mProgressDialog.dismissAllowingStateLoss();
        }
    }

    @Override
    public CustomProgressDialog generateProgressDialog(int taskId) {
        //dummy method for compilation, implementation handled in those subclasses that need it
        return null;
    }

    public Pair<Detail, TreeReference> requestEntityContext() {
        return null;
    }

    /**
     * Interface to perform additional setup code when adding an ActionBar
     * using the {@link #tryToAddActionSearchBar(android.app.Activity,
     * android.view.Menu,
     * org.commcare.android.framework.CommCareActivity.ActionBarInstantiator)}
     * tryToAddActionSearchBar} method.
     */
    public interface ActionBarInstantiator {
        void onActionBarFound(MenuItem searchItem, SearchView searchView);
    }

    /**
     * Tries to add actionBar to current Activity and hides the current search
     * widget and runs ActionBarInstantiator if it exists. Used in
     * EntitySelectActivity and FormRecordListActivity.
     *
     * @param act          Current activity
     * @param menu         Menu passed through onCreateOptionsMenu
     * @param instantiator Optional ActionBarInstantiator for additional setup
     *                     code.
     */
    public void tryToAddActionSearchBar(Activity act, Menu menu,
                                        ActionBarInstantiator instantiator) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            MenuInflater inflater = act.getMenuInflater();
            inflater.inflate(org.commcare.dalvik.R.menu.activity_report_problem, menu);

            MenuItem searchItem = menu.findItem(org.commcare.dalvik.R.id.search_action_bar);
            SearchView searchView =
                    (SearchView) searchItem.getActionView();
            if (searchView != null) {
                int[] searchViewStyle =
                        AndroidUtil.getThemeColorIDs(this,
                                new int[]{org.commcare.dalvik.R.attr.searchbox_action_bar_color});
                int id = searchView.getContext()
                        .getResources()
                        .getIdentifier("android:id/search_src_text", null, null);
                TextView textView = (TextView) searchView.findViewById(id);
                textView.setTextColor(searchViewStyle[0]);
                if (instantiator != null) {
                    instantiator.onActionBarFound(searchItem, searchView);
                }
            }

            View bottomSearchWidget = act.findViewById(org.commcare.dalvik.R.id.searchfooter);
            if (bottomSearchWidget != null) {
                bottomSearchWidget.setVisibility(View.GONE);
            }
        }
    }

    /**
     * Whether or not the "Back" action makes sense for this activity.
     *
     * @return True if "Back" is a valid concept for the Activity ande should be shown
     * in the action bar if available. False otherwise.
     */
    public boolean isBackEnabled() {
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent mv) {
        return !(mGestureDetector == null || !mGestureDetector.onTouchEvent(mv)) || super.dispatchTouchEvent(mv);

    }

    @Override
    public boolean onDown(MotionEvent arg0) {
        return false;
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        if (isHorizontalSwipe(this, e1, e2)) {
            if (velocityX <= 0) {
                return onForwardSwipe();
            }
            return onBackwardSwipe();
        }

        return false;
    }

    /**
     * Action to take when user swipes forward during activity.
     *
     * @return Whether or not the swipe was handled
     */
    protected boolean onForwardSwipe() {
        return false;
    }

    /**
     * Action to take when user swipes backward during activity.
     *
     * @return Whether or not the swipe was handled
     */
    protected boolean onBackwardSwipe() {
        return false;
    }

    @Override
    public void onLongPress(MotionEvent arg0) {
        // ignore
    }

    @Override
    public boolean onScroll(MotionEvent arg0, MotionEvent arg1, float arg2, float arg3) {
        return false;
    }

    @Override
    public void onShowPress(MotionEvent arg0) {
        // ignore
    }

    @Override
    public boolean onSingleTapUp(MotionEvent arg0) {
        return false;
    }

    /**
     * Decide if two given MotionEvents represent a swipe.
     *
     * @return True iff the movement is a definitive horizontal swipe.
     */
    public static boolean isHorizontalSwipe(Activity activity, MotionEvent e1, MotionEvent e2) {
        DisplayMetrics dm = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(dm);

        //details of the motion itself
        float xMov = Math.abs(e1.getX() - e2.getX());
        float yMov = Math.abs(e1.getY() - e2.getY());

        double angleOfMotion = ((Math.atan(yMov / xMov) / Math.PI) * 180);


        // for all screens a swipe is left/right of at least .25" and at an angle of no more than 30
        //degrees
        int xPixelLimit = (int) (dm.xdpi * .25);

        return xMov > xPixelLimit && angleOfMotion < 30;
    }

    /**
     * Rebuild the activity's menu options based on the current state of the activity.
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public void rebuildMenus() {
        // CommCare-159047: this method call rebuilds the options menu
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            invalidateOptionsMenu();
        } else {
            supportInvalidateOptionsMenu();
        }
    }

    public Spannable localize(String key) {
        return MarkupUtil.localizeStyleSpannable(this, key);
    }

    public Spannable localize(String key, String[] args) {
        return MarkupUtil.localizeStyleSpannable(this, key, args);
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public void refreshActionBar() {
        FragmentManager fm = this.getSupportFragmentManager();
        BreadcrumbBarFragment bar = (BreadcrumbBarFragment) fm.findFragmentByTag("breadcrumbs");
        bar.refresh(this);
    }
}
