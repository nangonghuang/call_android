package im.zego.calluikit.ui.call;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.KeyguardManager.KeyguardDismissCallback;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.StringRes;
import androidx.lifecycle.ViewModelProvider;

import com.blankj.utilcode.util.ActivityUtils;
import com.blankj.utilcode.util.ResourceUtils;
import com.blankj.utilcode.util.ToastUtils;
import com.gyf.immersionbar.ImmersionBar;
import com.jeremyliao.liveeventbus.LiveEventBus;

import im.zego.callsdk.listener.ZegoCallingState;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import im.zego.callsdk.core.interfaces.ZegoCallService;
import im.zego.callsdk.core.interfaces.ZegoDeviceService;
import im.zego.callsdk.core.interfaces.ZegoStreamService;
import im.zego.callsdk.core.interfaces.ZegoUserService;
import im.zego.callsdk.core.manager.ZegoServiceManager;
import im.zego.callsdk.model.ZegoCallType;
import im.zego.callsdk.model.ZegoNetWorkQuality;
import im.zego.callsdk.model.ZegoUserInfo;
import im.zego.calluikit.R;
import im.zego.calluikit.ZegoCallManager;
import im.zego.calluikit.constant.Constants;
import im.zego.calluikit.databinding.ActivityCallBinding;
import im.zego.calluikit.ui.BaseActivity;
import im.zego.calluikit.ui.dialog.VideoSettingsDialog;
import im.zego.calluikit.ui.viewmodel.VideoConfigViewModel;
import im.zego.calluikit.utils.AvatarHelper;
import im.zego.calluikit.utils.TokenManager;
import im.zego.zegoexpress.constants.ZegoRoomState;

public class CallActivity extends BaseActivity<ActivityCallBinding> {

    private static final String TAG = "CallActivity";

    private static final String USER_INFO = "user_info";

    private ZegoUserInfo userInfo;
    private Handler handler = new Handler(Looper.getMainLooper());

    private Runnable finishRunnable = () -> {
        CallStateManager.getInstance().setCallState(null, CallStateManager.TYPE_CALL_MISSED);
    };
    private Runnable timeCountRunnable = new Runnable() {
        @Override
        public void run() {
            time++;
            String timeFormat;
            if (time / 3600 > 0) {
                timeFormat = String
                    .format(Locale.getDefault(), "%02d:%02d:%02d", time / 3600, time / 60 - 60 * (time / 3600),
                        time % 60);
            } else {
                timeFormat = String.format(Locale.getDefault(), "%02d:%02d", time / 60, time % 60);
            }
            binding.callTime.setText(timeFormat);
            LiveEventBus
                .get(Constants.EVENT_TIMER_CHANGE_KEY, String.class)
                .post(timeFormat);
            handler.postDelayed(timeCountRunnable, 1000);
        }
    };

    private long time;
    private CallStateManager.CallStateChangedListener callStateChangedListener;
    private VideoSettingsDialog videoSettingsDialog;

    public static void startCallActivity(ZegoUserInfo userInfo) {
        Log.d(TAG, "startCallActivity() called with: userInfo = [" + userInfo + "]");
        Activity topActivity = ActivityUtils.getTopActivity();
        Intent intent = new Intent(topActivity, CallActivity.class);
        intent.putExtra(USER_INFO, userInfo);
        topActivity.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        userInfo = (ZegoUserInfo) getIntent().getSerializableExtra(USER_INFO);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

            keyguardManager.requestDismissKeyguard(this, new KeyguardDismissCallback() {
                @Override
                public void onDismissError() {
                    super.onDismissError();
                    Log.d(TAG, "onDismissError() called");
                }

                @Override
                public void onDismissSucceeded() {
                    super.onDismissSucceeded();
                    Log.d(TAG, "onDismissSucceeded() called");
                }

                @Override
                public void onDismissCancelled() {
                    super.onDismissCancelled();
                    Log.d(TAG, "onDismissCancelled() called");
                }
            });
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        super.onCreate(savedInstanceState);
        ImmersionBar.with(this).reset().init();

        VideoConfigViewModel videoConfigViewModel = new ViewModelProvider(this).get(VideoConfigViewModel.class);
        videoConfigViewModel.init();
        videoConfigViewModel.updateVideoConfig();
        videoSettingsDialog = new VideoSettingsDialog(this, videoConfigViewModel);

        initView();
        startObserve();
    }

    private void startObserve() {
        LiveEventBus
            .get(Constants.EVENT_MINIMAL, Boolean.class)
            .observe(this, isMinimal -> {
                if (isMinimal) {
                    moveTaskToBack(true);
                }
                setExcludeFromRecents(isMinimal);
            });
        LiveEventBus.get(Constants.EVENT_SHOW_SETTINGS, Boolean.class).observe(this, isVideoCall -> {
            videoSettingsDialog.setIsVideoCall(isVideoCall);
            videoSettingsDialog.show();
        });
        LiveEventBus
            .get(Constants.EVENT_CANCEL_CALL, String.class)
            .observe(this, s -> {
                ZegoCallService callService = ZegoServiceManager.getInstance().callService;
                callService.cancelCall(errorCode -> {
                    if (errorCode == 0) {
                        CallStateManager.getInstance().setCallState(userInfo, CallStateManager.TYPE_CALL_CANCELED);
                    }
                });
            });
        LiveEventBus
            .get(Constants.EVENT_END_CALL, String.class)
            .observe(this, s -> {
                ZegoCallService callService = ZegoServiceManager.getInstance().callService;
                callService.endCall(errorCode -> {
                    if (errorCode == 0) {
                        CallStateManager.getInstance().setCallState(userInfo, CallStateManager.TYPE_CALL_COMPLETED);
                    }
                });
            });
    }

    private void setExcludeFromRecents(boolean isMinimal) {
        Log.d(TAG, "setExcludeFromRecents() called with: isMinimal = [" + isMinimal + "]");
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            List<ActivityManager.AppTask> tasks = am.getAppTasks();
            for (ActivityManager.AppTask task : tasks) {
                if (getTaskId() == task.getTaskInfo().id) {
                    task.setExcludeFromRecents(isMinimal);
                }
            }
        }
    }

    private void initView() {
        int typeOfCall = CallStateManager.getInstance().getCallState();
        updateUi(typeOfCall);
        if (CallStateManager.TYPE_CALL_COMPLETED == typeOfCall) {
            finishActivityDelayed();
            return;
        }

        initDeviceState(typeOfCall);

        callStateChangedListener = new CallStateManager.CallStateChangedListener() {
            @Override
            public void onCallStateChanged(ZegoUserInfo userInfo, int before, int after) {
                boolean beforeIsOutgoing = (before == CallStateManager.TYPE_OUTGOING_CALLING_VOICE) ||
                    (before == CallStateManager.TYPE_OUTGOING_CALLING_VIDEO);
                boolean beforeIsInComing = (before == CallStateManager.TYPE_INCOMING_CALLING_VOICE) ||
                    (before == CallStateManager.TYPE_INCOMING_CALLING_VIDEO);
                boolean afterIsAccept = (after == CallStateManager.TYPE_CONNECTED_VOICE) ||
                    (after == CallStateManager.TYPE_CONNECTED_VIDEO);
                if ((beforeIsOutgoing || beforeIsInComing) && afterIsAccept) {
                    time = 0;
                    handler.post(timeCountRunnable);
                    handler.removeCallbacks(finishRunnable);
                    ZegoDeviceService deviceService = ZegoServiceManager.getInstance().deviceService;
                    deviceService.enableSpeaker(false);
                } else if (after == CallStateManager.TYPE_CALL_CANCELED) {
                    updateStateText(R.string.call_page_status_canceled);
                    finishActivityDelayed();
                } else if (after == CallStateManager.TYPE_CALL_COMPLETED) {
                    updateStateText(R.string.call_page_status_completed);
                    ToastUtils.showShort(R.string.call_page_status_completed);
                    finishActivityDelayed();
                } else if (after == CallStateManager.TYPE_CALL_MISSED) {
                    updateStateText(R.string.call_page_status_missed);
                    finishActivityDelayed();
                } else if (after == CallStateManager.TYPE_CALL_DECLINE) {
                    updateStateText(R.string.call_page_status_declined);
                    finishActivityDelayed();
                }
                updateUi(after);
            }
        };
        CallStateManager.getInstance().addListener(callStateChangedListener);
    }

    private void updateStateText(@StringRes int stringID) {
        binding.layoutOutgoingCall.updateStateText(stringID);
        binding.layoutIncomingCall.updateStateText(stringID);
    }

    private void initDeviceState(int typeOfCall) {
        ZegoUserService userService = ZegoServiceManager.getInstance().userService;
        ZegoCallService callService = ZegoServiceManager.getInstance().callService;
        ZegoDeviceService deviceService = ZegoServiceManager.getInstance().deviceService;
        ZegoStreamService streamService = ZegoServiceManager.getInstance().streamService;

        deviceService.enableSpeaker(false);
        deviceService.useFrontCamera(true);

        String token = TokenManager.getInstance().tokenWrapper.token;
        if (typeOfCall == CallStateManager.TYPE_OUTGOING_CALLING_VOICE) {
            callService.callUser(userInfo, ZegoCallType.Voice, token, errorCode -> {
                if (errorCode == 0) {
                    deviceService.enableMic(true);
                } else {
                    showWarnTips(getString(R.string.call_page_call_fail, errorCode));
                    finishActivityDelayed();
                }
            });
        } else if (typeOfCall == CallStateManager.TYPE_OUTGOING_CALLING_VIDEO) {
            callService.callUser(userInfo, ZegoCallType.Video, token, errorCode -> {
                if (errorCode == 0) {
                    TextureView textureView = binding.layoutOutgoingCall.getTextureView();
                    deviceService.enableMic(true);
                    deviceService.enableCamera(true);
                    streamService.startPlaying(userService.getLocalUserInfo().userID, textureView);
                } else {
                    showWarnTips(getString(R.string.call_page_call_fail, errorCode));
                    finishActivityDelayed();
                }
            });
        } else if (typeOfCall == CallStateManager.TYPE_INCOMING_CALLING_VIDEO) {
            handler.postDelayed(finishRunnable, 62 * 1000);
        } else if (typeOfCall == CallStateManager.TYPE_INCOMING_CALLING_VOICE) {
            handler.postDelayed(finishRunnable, 62 * 1000);
        } else if (typeOfCall == CallStateManager.TYPE_CONNECTED_VOICE) {
            handler.post(timeCountRunnable);
            deviceService.enableMic(true);
            deviceService.enableSpeaker(false);
            handler.removeCallbacks(finishRunnable);
        } else if (typeOfCall == CallStateManager.TYPE_CONNECTED_VIDEO) {
            handler.post(timeCountRunnable);
            deviceService.enableMic(true);
            deviceService.enableSpeaker(false);
            deviceService.enableCamera(true);
            handler.removeCallbacks(finishRunnable);
        }
    }

    private void updateUi(int type) {
        binding.layoutOutgoingCall.setUserInfo(userInfo);
        binding.layoutOutgoingCall.setCallType(type);
        binding.layoutIncomingCall.setCallType(type);
        binding.layoutIncomingCall.setUserInfo(userInfo);
        binding.layoutConnectedVoiceCall.setUserInfo(userInfo);
        binding.layoutConnectedVideoCall.setUserInfo(userInfo);
        int resourceID = AvatarHelper.getBlurResourceID(userInfo.userName);
        binding.callUserBg.setImageDrawable(ResourceUtils.getDrawable(resourceID));

        switch (type) {
            case CallStateManager.TYPE_INCOMING_CALLING_VOICE:
            case CallStateManager.TYPE_INCOMING_CALLING_VIDEO:
                binding.layoutIncomingCall.setVisibility(View.VISIBLE);
                binding.layoutOutgoingCall.setVisibility(View.GONE);
                binding.layoutConnectedVideoCall.setVisibility(View.GONE);
                binding.layoutConnectedVoiceCall.setVisibility(View.GONE);
                binding.callTime.setVisibility(View.GONE);
                break;
            case CallStateManager.TYPE_CONNECTED_VOICE:
                binding.layoutIncomingCall.setVisibility(View.GONE);
                binding.layoutOutgoingCall.setVisibility(View.GONE);
                binding.layoutConnectedVideoCall.setVisibility(View.GONE);
                binding.layoutConnectedVoiceCall.setVisibility(View.VISIBLE);
                binding.callTime.setVisibility(View.VISIBLE);
                break;
            case CallStateManager.TYPE_CONNECTED_VIDEO:
                binding.layoutIncomingCall.setVisibility(View.GONE);
                binding.layoutOutgoingCall.setVisibility(View.GONE);
                binding.layoutConnectedVideoCall.setVisibility(View.VISIBLE);
                binding.layoutConnectedVoiceCall.setVisibility(View.GONE);
                binding.callTime.setVisibility(View.VISIBLE);
                break;
            case CallStateManager.TYPE_OUTGOING_CALLING_VOICE:
            case CallStateManager.TYPE_OUTGOING_CALLING_VIDEO:
                binding.layoutIncomingCall.setVisibility(View.GONE);
                binding.layoutOutgoingCall.setVisibility(View.VISIBLE);
                binding.layoutConnectedVideoCall.setVisibility(View.GONE);
                binding.layoutConnectedVoiceCall.setVisibility(View.GONE);
                binding.callTime.setVisibility(View.GONE);
                break;
            case CallStateManager.TYPE_CALL_COMPLETED:
                binding.layoutConnectedVideoCall.setVisibility(View.GONE);
                binding.layoutConnectedVoiceCall.setVisibility(View.GONE);
                break;
        }
    }

    private void finishActivityDelayed() {
        handler.postDelayed(() -> {
            finish();
        }, 1000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        CallStateManager.getInstance().setCallState(userInfo, CallStateManager.TYPE_NO_CALL);
        CallStateManager.getInstance().removeListener(callStateChangedListener);
        ZegoServiceManager.getInstance().deviceService.listeners.clear();
    }

    @Override
    public void onBackPressed() {
    }

    public void onUserInfoUpdated(ZegoUserInfo userInfo) {
        if (Objects.equals(this.userInfo, userInfo)) {
            this.userInfo = userInfo;
        }
        binding.layoutIncomingCall.onUserInfoUpdated(userInfo);
        binding.layoutOutgoingCall.onUserInfoUpdated(userInfo);
        binding.layoutConnectedVideoCall.onUserInfoUpdated(userInfo);
        binding.layoutConnectedVoiceCall.onUserInfoUpdated(userInfo);
    }

    public void onNetworkQuality(String userID, ZegoNetWorkQuality quality) {
        if (quality == ZegoNetWorkQuality.Bad) {
            if (userID.equals(ZegoCallManager.getInstance().getLocalUserInfo().userID)) {
                showLoading(getString(R.string.network_connnect_me_unstable), false);
            } else {
                showLoading(getString(R.string.network_connnect_other_unstable), false);
            }
        } else {
            dismissLoading();
        }
    }

    public void onCallingStateUpdated(ZegoCallingState state) {
        if (state == ZegoCallingState.DISCONNECTED) {
            showLoading(getString(R.string.call_page_call_disconnected), true);
        } else {
            dismissLoading();
        }
    }
}