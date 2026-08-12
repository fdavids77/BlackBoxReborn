package top.niunaijun.blackbox.fake.delegate;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;

import android.os.Build;
import android.os.Parcel;

import java.util.HashMap;
import java.util.Map;

import black.android.app.BRIServiceConnectionO;
import top.niunaijun.blackbox.utils.compat.BuildCompat;


public class ServiceConnectionDelegate extends IServiceConnection.Stub {
    private static final Map<IBinder, ServiceConnectionDelegate> sServiceConnectDelegate = new HashMap<>();
    private final IServiceConnection mConn;
    private final ComponentName mComponentName;

    private ServiceConnectionDelegate(IServiceConnection mConn, ComponentName targetComponent) {
        this.mConn = mConn;
        this.mComponentName = targetComponent;
    }

    public static ServiceConnectionDelegate getDelegate(IBinder iBinder) {
        return sServiceConnectDelegate.get(iBinder);
    }

    public static IServiceConnection createProxy(IServiceConnection base, Intent intent) {
        final IBinder iBinder = base.asBinder();
        ServiceConnectionDelegate delegate = sServiceConnectDelegate.get(iBinder);
        if (delegate == null) {
            try {
                iBinder.linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        sServiceConnectDelegate.remove(iBinder);
                        iBinder.unlinkToDeath(this, 0);
                    }
                }, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            delegate = new ServiceConnectionDelegate(base, intent.getComponent());
            sServiceConnectDelegate.put(iBinder, delegate);
        }
        return delegate;
    }

    @Override
    public void connected(ComponentName name, IBinder service) throws RemoteException {
        connected(name, service, false);
    }

    /**
     * Fix 8 (API 37): IServiceConnection gained a new IBinderSession parameter in Android 17.
     * Since we compile against SDK 36, IBinderSession isn't available as a type.
     * Override onTransact() to intercept the Binder dispatch before super() tries to call
     * the abstract 4-param connected() method and throws AbstractMethodError.
     *
     * Transaction code FIRST_CALL_TRANSACTION (1) is used for connected() in the Stub.
     * On API 37 the Parcel layout is: [descriptor] ComponentName IBinder(service)
     *   IBinder(IBinderSession) boolean(dead)
     * We read all four, discard the session, and delegate to our 3-param implementation.
     */
    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (Build.VERSION.SDK_INT >= 37
                && code == android.os.IBinder.FIRST_CALL_TRANSACTION) {
            data.enforceInterface(DESCRIPTOR);
            // AIDL always precedes a nullable Parcelable arg with a presence flag
            // (readInt() != 0) before the actual writeToParcel payload — must be
            // consumed first or every subsequent read is off by 4 bytes.
            ComponentName name = null;
            if (data.readInt() != 0) {
                name = ComponentName.CREATOR.createFromParcel(data);
            }
            IBinder service = data.readStrongBinder();
            // Read and discard IBinderSession (it extends IBinder internally)
            if (data.dataAvail() > 4) { // more than a boolean left → session IBinder present
                data.readStrongBinder();
            }
            boolean dead = data.readBoolean();
            this.connected(name, service, dead);
            if (reply != null) reply.writeNoException();
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    public void connected(ComponentName name, IBinder service, boolean dead) throws RemoteException {
        if (BuildCompat.isOreo()) {
            BRIServiceConnectionO.get(mConn).connected(mComponentName, service, dead);
        } else {
            mConn.connected(name, service);
        }
    }
}
