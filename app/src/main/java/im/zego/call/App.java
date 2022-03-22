package im.zego.call;

import android.app.Application;
import com.blankj.utilcode.util.Utils;
import com.tencent.mmkv.MMKV;
import im.zego.call.auth.AuthInfoManager;
import im.zego.callsdk.service.ZegoServiceManager;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Utils.init(this);
        AuthInfoManager.getInstance().init(this);

        MMKV.initialize(this);

        long appID = AuthInfoManager.getInstance().getAppID();
        String appSign = AuthInfoManager.getInstance().getAppSign();
        ZegoServiceManager.getInstance().init(appID, appSign, this);
    }
}
