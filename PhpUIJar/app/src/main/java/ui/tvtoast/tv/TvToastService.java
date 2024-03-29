package ui.tvtoast.tv;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnKeyListener;
import android.view.WindowManager;

import java.util.HashMap;
import java.util.Map;

import fany.phpuijar.R;
import ui.tvtoast.ITvToastService.Stub;
import ui.tvtoast.TvToast;
import ui.tvtoast.tv.TvToastView.MessageState;

/**
 * Application calls are made through any of the 3 entry points.
 * -- showTvToastMessage
 * -- cancelTvToastMessage
 * -- handleProcessDeath
 * <p>
 * Each application call comes on the corresponding binder thread.
 * The state of the State Machine is accessed/modified from any of these threads.
 * Apart from the application [binder] threads, Main [UI] thread of TvToastService
 * also accesses the state (but doesn't modify it).
 * <p>
 * Hence the synchorinzation.
 */
public class TvToastService extends Service implements OnKeyListener, MessageState.IStateTransitionCallback {

    private static final String TAG = "TvToastService";

    private WindowManager mWM;
    private WindowManager.LayoutParams mWindowParams;

    private TvToastInfo mStashedMessage;
    private TvToastInfo mCurrentMessage;

    private View mView;
    private TvToastView mTvToastView;

    private final Object mStateLock = new Object();

    private Handler mUiHandler = new UiHandler();
    private Handler mTimeOutHandler = new TimeOutHandler();
    private Map<IBinder, TvToastContext> mTvToastContextMap = new HashMap<IBinder, TvToastContext>();

    private final class UiHandler extends Handler {

        private static final int MESSAGE_ADD_TV_TOAST_MESSAGE = 2000;
        private static final int MESSAGE_REMOVE_TV_TOAST_VIEW_IF_ADDED = 2001;

        /*
     * Handle UI related messages posted by Application (binder) threads. [Thread-safe]
     * Requires Read-lock of mState
     */
        @Override
        public void handleMessage(Message msg) {
            synchronized (mStateLock) {
                switch (msg.what) {

                    case MESSAGE_REMOVE_TV_TOAST_VIEW_IF_ADDED:
                        removeTvToastViewIfAlreadyAttached();
                        break;

                    case MESSAGE_ADD_TV_TOAST_MESSAGE:
                        addTvToastView();
                        break;
                }
            }
        }
    }

    private final class TimeOutHandler extends Handler {
        private static final int MESSAGE_REMOVE_TV_TOAST_MESSAGE = 1000;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {

                case MESSAGE_REMOVE_TV_TOAST_MESSAGE:
                    removeTvToastMessageLocked();
                    break;
            }
        }
    }

    public void onCreate() {
        super.onCreate();
        /*
         * create ui.dialog.tv toast view
		 */
        createTvToastView();
    }

    private final Stub mBinder = new Stub() {

        /**
         * Entry #1: Entry for application [binder] threads
         */
        @Override
        public void showTvToastMessage(IBinder contextToken, TvToast msg) throws RemoteException {
            addTvToastContext(contextToken);
            showTvToastMessageInternal(contextToken, msg);
        }

        /**
         * Entry #2: Entry for application [binder] threads
         */
        @Override
        public void cancelTvToastMessage(IBinder contextToken, TvToast msg) throws RemoteException {
            addTvToastContext(contextToken);
            cancelTvToastMessageInternal(msg);
        }

    };

    private final class TvToastContext implements IBinder.DeathRecipient {

        private final IBinder mContextToken;

        public TvToastContext(IBinder contextToken) {
            this.mContextToken = contextToken;
        }

        /**
         * Entry #3: Entry for application [binder] threads
         */
        @Override
        public void binderDied() {
            Log.d(TAG, "process linked to the binder: " + mContextToken + " has died.");
            removeTvToastContext(mContextToken);
            handleProcessDeath(mContextToken);
        }
    }

    private final class TvToastInfo {

        private TvToast mTvToast;
        private IBinder mContextToken;

        private TvToastInfo(TvToast mTvToast, IBinder mContextToken) {
            this.mTvToast = mTvToast;
            this.mContextToken = mContextToken;
        }

        public TvToast getTvToast() {
            return mTvToast;
        }

        public IBinder getContextToken() {
            return mContextToken;
        }

        public void setTvToast(TvToast mTvToast) {
            this.mTvToast = mTvToast;
        }

        public void setContextToken(IBinder mContextToken) {
            this.mContextToken = mContextToken;
        }
    }

    /*
     * remove stashed and/or current msg if the process that posted is dead [Thread-safe]
     * Requires write-lock of mState
     */
    private void handleProcessDeath(IBinder contextToken) {
        synchronized (mStateLock) {
            if (mCurrentMessage != null && contextToken.equals(mCurrentMessage.getContextToken())) {
                Log.d(TAG, "handleProcessDeath, removing current shown msg: " + mCurrentMessage.getTvToast());
                if (mCurrentMessage.getTvToast() != null) {
                    removeTvToastMessage();
                }
            }

            if (mStashedMessage != null && contextToken.equals(mStashedMessage.getContextToken())) {
                Log.d(TAG, "handleProcessDeath, removing stashed msg: " + mStashedMessage.getTvToast());
                if (mStashedMessage.getTvToast() != null) {
                    unStashMessage(mStashedMessage.getTvToast());
                }
            }
        }
    }

    /*
     * add an entry in Tv Toast Context map, if not already present [Thread-safe]
     * Requires write-lock of mTvToastContextMap
     */
    private void addTvToastContext(IBinder contextToken) {
        synchronized (mTvToastContextMap) {
            if (!mTvToastContextMap.containsKey(contextToken)) {
                TvToastContext tvToastContext = new TvToastContext(contextToken);
                try {
                    contextToken.linkToDeath(tvToastContext, 0);
                    mTvToastContextMap.put(contextToken, tvToastContext);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /*
     * remove the entry from Tv Toast Context map, if present [Thread-safe]
     * Requires write-lock of mTvToastContextMap
     */
    private void removeTvToastContext(IBinder contextToken) {
        synchronized (mTvToastContextMap) {
            mTvToastContextMap.remove(contextToken);
        }
    }

    /*
     * show Tv Toast [Thread-safe]
     * Requires write-lock of mState
     */
    private void showTvToastMessageInternal(IBinder contextToken, TvToast msg) {
        synchronized (mStateLock) {
            Log.d(TAG, "showTvToastMessageInternal ---- TvToastService ---- tvToastMsg: " + msg);
            if (!msg.isPersistent() && mCurrentMessage != null) {
                stashMessage(mCurrentMessage.getTvToast(), contextToken);
            }
            addTvToastMessage(msg, contextToken);
        }
    }

    /*
     * cancel Tv Toast [Thread-safe]
     * Requires write-lock of mState
     */
    private void cancelTvToastMessageInternal(TvToast msg) {
        synchronized (mStateLock) {
            Log.d(TAG, "cancelTvToastMessageInternal ---- TvToastService ---- tvToastMsg: " + msg);
            if (mCurrentMessage != null && mCurrentMessage.getTvToast() != null && mCurrentMessage.getTvToast().equals(msg)) {
                removeTvToastMessage();
            } else if (mStashedMessage != null && mStashedMessage.getTvToast() != null && mStashedMessage.getTvToast().equals(msg)) {
                unStashMessage(msg);
            }
        }
    }

    /*
     * create Tv Toast View
     */
    private void createTvToastView() {
        mWM = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        mWindowParams = new WindowManager.LayoutParams();
        mWindowParams.format = PixelFormat.TRANSLUCENT;
        mWindowParams.width = WindowManager.LayoutParams.MATCH_PARENT;
        mWindowParams.height = (int) getResources().getDimension(R.dimen.flashmessage_long_container_height_double);
        mWindowParams.type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        mWindowParams.gravity = Gravity.BOTTOM | Gravity.END;
        mWindowParams.x = 0;
        mWindowParams.y = (int) getResources().getDimension(R.dimen.flashmessage_alert_padding_left_height);
        mWindowParams.windowAnimations = 0;

        mView = View.inflate(this, R.layout.tv_toast, null);
        mTvToastView = mView.findViewById(R.id.tvToast);
        mTvToastView.setStateTransitionCallback(this);
        Log.d(TAG, "createTvToastView ---- TvToastService ---- end tvToastView: " + mView);
    }

    /*
 * Remove TvToast View [ ---------- Runs on UI thread ---------- ] [Not Thread-safe]
 * Calling methods should ensure multi-thread synchronization.
 */
    private void removeTvToastViewIfAlreadyAttached() {
        if (mView != null && mView.getParent() != null) {
            mWM.removeViewImmediate(mView);
        }
    }

    /*
 * Adds TvToast View [ ---------- Runs on UI thread ---------- ] [Not Thread-safe]
 * Calling methods should ensure multi-thread synchronization.
 */
    private void addTvToastView() {
        if (mCurrentMessage != null) {
            TvToast currentMessage = mCurrentMessage.getTvToast();

            mTvToastView.setFocusable(currentMessage.isFocusable());
            mTvToastView.setOnKeyListener(currentMessage.isFocusable() ? this : null);
            mTvToastView.setMessage(currentMessage.getMessage());
            if (currentMessage.getIcon() == null) {
                mTvToastView.setIcon((currentMessage.getIconRes() != -1 && currentMessage.getIconRes() != 0) ?
                        getResources().getDrawable(currentMessage.getIconRes()) : null);
            } else {
                mTvToastView.setIcon(currentMessage.getIcon());
            }
            Log.d(TAG, "addTvToastView - mCurrentMessage: " + currentMessage);
            removeTvToastViewIfAlreadyAttached();
            mWM.addView(mView, mWindowParams);
        }
    }

    /*
     * set message to Tv Toast View and add to window [Not Thread-safe]
     * Calling methods should ensure multi-thread synchronization.
     */
    private void addTvToastMessage(TvToast msg, IBinder contextToken) {
        mCurrentMessage = new TvToastInfo(msg, contextToken);

        mTimeOutHandler.removeMessages(TimeOutHandler.MESSAGE_REMOVE_TV_TOAST_MESSAGE);

		/*
         * remove ui.dialog.tv toast view if already attached
		 */
        mUiHandler.removeMessages(UiHandler.MESSAGE_REMOVE_TV_TOAST_VIEW_IF_ADDED);
        mUiHandler.sendEmptyMessage(UiHandler.MESSAGE_REMOVE_TV_TOAST_VIEW_IF_ADDED);

		/*
		 * adjust window layout params
		 */
        mWindowParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if (!mCurrentMessage.getTvToast().isFocusable()) {
            mWindowParams.flags = mWindowParams.flags | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
		
		/*
		 * add view
		 */
        mUiHandler.removeMessages(UiHandler.MESSAGE_ADD_TV_TOAST_MESSAGE);
        mUiHandler.sendEmptyMessage(UiHandler.MESSAGE_ADD_TV_TOAST_MESSAGE);
    }

    /*
     * stash message if required [Not Thread-safe]
     * Calling methods should ensure multi-thread synchronization.
     */
    private void stashMessage(TvToast msg, IBinder contextToken) {
        if (msg != null && msg.isPersistent()) {
            mStashedMessage = new TvToastInfo(msg, contextToken);
        }
    }

    /*
     * unstash message if necessary [Not Thread-safe]
     * Calling methods should ensure multi-thread synchronization.
     */
    private void unStashMessage(TvToast msg) {
        if (mStashedMessage != null && mStashedMessage.getTvToast() != null && mStashedMessage.getTvToast().equals(msg)) {
            mStashedMessage = null;
        }
    }

    /*
 * Remove TvToast Message [Thread-safe]
 * Requires write-lock of mState
 */
    private void removeTvToastMessageLocked() {
        synchronized (mStateLock) {
            removeTvToastMessage();
        }
    }

    /*
     * removed Tv Toast View from window [Not Thread-safe]
     * Calling methods should ensure multi-thread synchronization.
     */
    private void removeTvToastMessage() {
        mCurrentMessage = null;

	    /*
	     * remove ui.dialog.tv toast view if already attached
	     */
        mUiHandler.removeMessages(UiHandler.MESSAGE_REMOVE_TV_TOAST_VIEW_IF_ADDED);
        mUiHandler.sendEmptyMessage(UiHandler.MESSAGE_REMOVE_TV_TOAST_VIEW_IF_ADDED);

        if (mStashedMessage != null && mStashedMessage.getTvToast() != null) {
            addTvToastMessage(mStashedMessage.getTvToast(), mStashedMessage.getContextToken());
            unStashMessage(mStashedMessage.getTvToast());
        }
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return mBinder;
    }

    @Override
    public boolean onKey(View view, int action, KeyEvent event) {
		/*
		 * if key event received, remove Tv Toast view
		 */
        removeTvToastMessageLocked();
        Log.d(TAG, "onKey");
        return true;
    }

    @Override
    public void onMessageStateChanged(int oldState, int newState) {

        switch (newState) {
            case MessageState.MESSAGE_READ_ONCE:
                mTimeOutHandler.removeMessages(TimeOutHandler.MESSAGE_REMOVE_TV_TOAST_MESSAGE);
                synchronized (mStateLock) {
                    // Once attained the state lock, check if msg_state is still the same
                    if (mTvToastView.getMessageState() == newState && mCurrentMessage != null) {
                        TvToast currentMessage = mCurrentMessage.getTvToast();
                        if (currentMessage.getTimeOutPeriod() != TvToast.TIME_OUT_DURATION_INFINITE) {
                            mTimeOutHandler.sendEmptyMessageDelayed(TimeOutHandler.MESSAGE_REMOVE_TV_TOAST_MESSAGE, currentMessage.getTimeOutPeriod());
                            Log.d(TAG, "start time out for msg: " + currentMessage);
                        }
                    }
                }
                break;
            case MessageState.MESSAGE_SHOW_READY:
                mTimeOutHandler.removeMessages(TimeOutHandler.MESSAGE_REMOVE_TV_TOAST_MESSAGE);
                break;
        }
    }

}
