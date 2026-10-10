package com.hsae.bluetoothsdk;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

public interface IBluetoothCallback extends IInterface {
    void onPowerStateChanged(int state) throws RemoteException;
    void onBatterLevelChanged(int level) throws RemoteException;
    void onSignelLevelChanged(int level) throws RemoteException;
    void onDeviceNameChanged(String name) throws RemoteException;
    void onPairStateChanged(String address, int state) throws RemoteException;
    void onConnectStateChanged(int profile, int status, int reason) throws RemoteException;
    void onDeviceConnectRequest(String address, String name, int cod, int reason) throws RemoteException;
    void onDevicePairRequest(String address, String name, boolean ssp) throws RemoteException;
    void onDeviceInquiried(String address, String name, int cod, int rssi, boolean complete) throws RemoteException;
    void onBtStateChanged(int state, String name) throws RemoteException;
    void onServiceConnected() throws RemoteException;
    void onServiceDisconnected() throws RemoteException;

    abstract class Stub extends Binder implements IBluetoothCallback {
        private static final String DESCRIPTOR = "com.hsae.bluetoothsdk.IBluetoothCallback";

        static final int TRANSACTION_onPowerStateChanged = 1;
        static final int TRANSACTION_onBatterLevelChanged = 2;
        static final int TRANSACTION_onSignelLevelChanged = 3;
        static final int TRANSACTION_onDeviceNameChanged = 4;
        static final int TRANSACTION_onPairStateChanged = 5;
        static final int TRANSACTION_onConnectStateChanged = 6;
        static final int TRANSACTION_onDeviceConnectRequest = 7;
        static final int TRANSACTION_onDevicePairRequest = 8;
        static final int TRANSACTION_onDeviceInquiried = 9;
        static final int TRANSACTION_onBtStateChanged = 10;
        static final int TRANSACTION_onServiceConnected = 11;
        static final int TRANSACTION_onServiceDisconnected = 12;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IBluetoothCallback asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IBluetoothCallback) {
                return (IBluetoothCallback) iin;
            }
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            switch (code) {
                case TRANSACTION_onPowerStateChanged: {
                    data.enforceInterface(DESCRIPTOR);
                    int state = data.readInt();
                    onPowerStateChanged(state);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_onBatterLevelChanged: {
                    data.enforceInterface(DESCRIPTOR);
                    int level = data.readInt();
                    onBatterLevelChanged(level);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_onSignelLevelChanged: {
                    data.enforceInterface(DESCRIPTOR);
                    int level = data.readInt();
                    onSignelLevelChanged(level);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_onDeviceNameChanged: {
                    data.enforceInterface(DESCRIPTOR);
                    String name = data.readString();
                    onDeviceNameChanged(name);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_onPairStateChanged: {
                    data.enforceInterface(DESCRIPTOR);
                    String address = data.readString();
                    int state = data.readInt();
                    onPairStateChanged(address, state);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_onConnectStateChanged: {
                    data.enforceInterface(DESCRIPTOR);
                    int profile = data.readInt();
                    int status = data.readInt();
                    int reason = data.readInt();
                    onConnectStateChanged(profile, status, reason);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_onDeviceConnectRequest: {
                    data.enforceInterface(DESCRIPTOR);
                    String address = data.readString();
                    String name = data.readString();
                    int cod = data.readInt();
                    int reason = data.readInt();
                    onDeviceConnectRequest(address, name, cod, reason);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_onDevicePairRequest: {
                    data.enforceInterface(DESCRIPTOR);
                    String address = data.readString();
                    String name = data.readString();
                    boolean ssp = data.readInt() != 0;
                    onDevicePairRequest(address, name, ssp);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_onDeviceInquiried: {
                    data.enforceInterface(DESCRIPTOR);
                    String address = data.readString();
                    String name = data.readString();
                    int cod = data.readInt();
                    int rssi = data.readInt();
                    boolean complete = data.readInt() != 0;
                    onDeviceInquiried(address, name, cod, rssi, complete);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_onBtStateChanged: {
                    data.enforceInterface(DESCRIPTOR);
                    int state = data.readInt();
                    String name = data.readString();
                    onBtStateChanged(state, name);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_onServiceConnected: {
                    data.enforceInterface(DESCRIPTOR);
                    onServiceConnected();
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_onServiceDisconnected: {
                    data.enforceInterface(DESCRIPTOR);
                    onServiceDisconnected();
                    reply.writeNoException();
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static class Proxy implements IBluetoothCallback {
            private final IBinder mRemote;

            Proxy(IBinder remote) {
                mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return mRemote;
            }

            @Override
            public void onPowerStateChanged(int state) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt(state);
                    mRemote.transact(TRANSACTION_onPowerStateChanged, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void onBatterLevelChanged(int level) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt(level);
                    mRemote.transact(TRANSACTION_onBatterLevelChanged, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void onSignelLevelChanged(int level) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt(level);
                    mRemote.transact(TRANSACTION_onSignelLevelChanged, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void onDeviceNameChanged(String name) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(name);
                    mRemote.transact(TRANSACTION_onDeviceNameChanged, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void onPairStateChanged(String address, int state) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(address);
                    _data.writeInt(state);
                    mRemote.transact(TRANSACTION_onPairStateChanged, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void onConnectStateChanged(int profile, int status, int reason) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt(profile);
                    _data.writeInt(status);
                    _data.writeInt(reason);
                    mRemote.transact(TRANSACTION_onConnectStateChanged, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void onDeviceConnectRequest(String address, String name, int cod, int reason) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(address);
                    _data.writeString(name);
                    _data.writeInt(cod);
                    _data.writeInt(reason);
                    mRemote.transact(TRANSACTION_onDeviceConnectRequest, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void onDevicePairRequest(String address, String name, boolean ssp) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(address);
                    _data.writeString(name);
                    _data.writeInt(ssp ? 1 : 0);
                    mRemote.transact(TRANSACTION_onDevicePairRequest, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void onDeviceInquiried(String address, String name, int cod, int rssi, boolean complete) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(address);
                    _data.writeString(name);
                    _data.writeInt(cod);
                    _data.writeInt(rssi);
                    _data.writeInt(complete ? 1 : 0);
                    mRemote.transact(TRANSACTION_onDeviceInquiried, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void onBtStateChanged(int state, String name) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt(state);
                    _data.writeString(name);
                    mRemote.transact(TRANSACTION_onBtStateChanged, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void onServiceConnected() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_onServiceConnected, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void onServiceDisconnected() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_onServiceDisconnected, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }
        }
    }
}
