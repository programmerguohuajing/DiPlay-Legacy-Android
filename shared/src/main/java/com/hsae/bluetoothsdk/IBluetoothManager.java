package com.hsae.bluetoothsdk;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

import com.hsae.bluetoothsdk.bean.BluetoothDevice;

import java.util.List;

public interface IBluetoothManager extends IInterface {

    boolean registCallback(IBluetoothCallback callback) throws RemoteException;

    boolean unregistCallback(IBluetoothCallback callback) throws RemoteException;

    void unBindService() throws RemoteException;

    void setBtName(String name) throws RemoteException;

    void btPowerOn() throws RemoteException;

    void btPowerOff() throws RemoteException;

    int getPowerStatus() throws RemoteException;

    int search(boolean start) throws RemoteException;

    int stopSearch() throws RemoteException;

    boolean isSearching() throws RemoteException;

    int pairDevice(String address, int type) throws RemoteException;

    void acceptPair(String address, boolean accept) throws RemoteException;

    void rejectPair(String address, boolean reject) throws RemoteException;

    boolean isParing() throws RemoteException;

    int removePair(String address) throws RemoteException;

    void connect(String address, String uuid) throws RemoteException;

    void connectHfp(String address) throws RemoteException;

    void connectA2dp(String address) throws RemoteException;

    int getConnectStatus(int profile) throws RemoteException;

    void disConnect() throws RemoteException;

    void disConnectA2dp() throws RemoteException;

    String getConnectDeviceName() throws RemoteException;

    String getConnectDeviceAddress() throws RemoteException;

    boolean supportInbandRing() throws RemoteException;

    void acceptConnect(int profile) throws RemoteException;

    void rejectConnect(int profile) throws RemoteException;

    List<BluetoothDevice> getPairedDevices() throws RemoteException;

    String getDeviceName() throws RemoteException;

    int getBatteryCharge() throws RemoteException;

    int getSignalValue() throws RemoteException;

    void setAutoPowerOn(boolean autoPowerOn) throws RemoteException;

    String getSdkVersion() throws RemoteException;

    boolean supportMusic() throws RemoteException;

    boolean supportPhone() throws RemoteException;

    abstract class Stub extends Binder implements IBluetoothManager {
        private static final String DESCRIPTOR = "com.hsae.bluetoothsdk.IBluetoothManager";

        static final int TRANSACTION_registCallback = 1;
        static final int TRANSACTION_unregistCallback = 2;
        static final int TRANSACTION_unBindService = 3;
        static final int TRANSACTION_setBtName = 4;
        static final int TRANSACTION_btPowerOn = 5;
        static final int TRANSACTION_btPowerOff = 6;
        static final int TRANSACTION_getPowerStatus = 7;
        static final int TRANSACTION_search = 8;
        static final int TRANSACTION_stopSearch = 9;
        static final int TRANSACTION_isSearching = 10;
        static final int TRANSACTION_pairDevice = 11;
        static final int TRANSACTION_acceptPair = 12;
        static final int TRANSACTION_rejectPair = 13;
        static final int TRANSACTION_isParing = 14;
        static final int TRANSACTION_removePair = 15;
        static final int TRANSACTION_connect = 16;
        static final int TRANSACTION_connectHfp = 17;
        static final int TRANSACTION_connectA2dp = 18;
        static final int TRANSACTION_getConnectStatus = 19;
        static final int TRANSACTION_disConnect = 20;
        static final int TRANSACTION_disConnectA2dp = 21;
        static final int TRANSACTION_getConnectDeviceName = 22;
        static final int TRANSACTION_getConnectDeviceAddress = 23;
        static final int TRANSACTION_supportInbandRing = 24;
        static final int TRANSACTION_acceptConnect = 25;
        static final int TRANSACTION_rejectConnect = 26;
        static final int TRANSACTION_getPairedDevices = 27;
        static final int TRANSACTION_getDeviceName = 28;
        static final int TRANSACTION_getBatteryCharge = 29;
        static final int TRANSACTION_getSignalValue = 30;
        static final int TRANSACTION_setAutoPowerOn = 31;
        static final int TRANSACTION_getSdkVersion = 32;
        static final int TRANSACTION_supportMusic = 33;
        static final int TRANSACTION_supportPhone = 34;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IBluetoothManager asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IBluetoothManager) {
                return (IBluetoothManager) iin;
            }
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code >= IBinder.FIRST_CALL_TRANSACTION && code <= IBinder.LAST_CALL_TRANSACTION) {
                data.enforceInterface(DESCRIPTOR);
            }
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            switch (code) {
                case TRANSACTION_registCallback: {
                    IBluetoothCallback cb = IBluetoothCallback.Stub.asInterface(data.readStrongBinder());
                    boolean result = registCallback(cb);
                    reply.writeNoException();
                    reply.writeInt(result ? 1 : 0);
                    return true;
                }
                case TRANSACTION_unregistCallback: {
                    IBluetoothCallback cb = IBluetoothCallback.Stub.asInterface(data.readStrongBinder());
                    boolean result = unregistCallback(cb);
                    reply.writeNoException();
                    reply.writeInt(result ? 1 : 0);
                    return true;
                }
                case TRANSACTION_unBindService: {
                    unBindService();
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_setBtName: {
                    String name = data.readString();
                    setBtName(name);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_btPowerOn: {
                    btPowerOn();
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_btPowerOff: {
                    btPowerOff();
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_getPowerStatus: {
                    int result = getPowerStatus();
                    reply.writeNoException();
                    reply.writeInt(result);
                    return true;
                }
                case TRANSACTION_search: {
                    boolean start = (data.readInt() != 0);
                    int result = search(start);
                    reply.writeNoException();
                    reply.writeInt(result);
                    return true;
                }
                case TRANSACTION_stopSearch: {
                    int result = stopSearch();
                    reply.writeNoException();
                    reply.writeInt(result);
                    return true;
                }
                case TRANSACTION_isSearching: {
                    boolean result = isSearching();
                    reply.writeNoException();
                    reply.writeInt(result ? 1 : 0);
                    return true;
                }
                case TRANSACTION_pairDevice: {
                    String address = data.readString();
                    int type = data.readInt();
                    int result = pairDevice(address, type);
                    reply.writeNoException();
                    reply.writeInt(result);
                    return true;
                }
                case TRANSACTION_acceptPair: {
                    String address = data.readString();
                    boolean accept = (data.readInt() != 0);
                    acceptPair(address, accept);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_rejectPair: {
                    String address = data.readString();
                    boolean reject = (data.readInt() != 0);
                    rejectPair(address, reject);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_isParing: {
                    boolean result = isParing();
                    reply.writeNoException();
                    reply.writeInt(result ? 1 : 0);
                    return true;
                }
                case TRANSACTION_removePair: {
                    String address = data.readString();
                    int result = removePair(address);
                    reply.writeNoException();
                    reply.writeInt(result);
                    return true;
                }
                case TRANSACTION_connect: {
                    String address = data.readString();
                    String uuid = data.readString();
                    connect(address, uuid);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_connectHfp: {
                    String address = data.readString();
                    connectHfp(address);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_connectA2dp: {
                    String address = data.readString();
                    connectA2dp(address);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_getConnectStatus: {
                    int profile = data.readInt();
                    int result = getConnectStatus(profile);
                    reply.writeNoException();
                    reply.writeInt(result);
                    return true;
                }
                case TRANSACTION_disConnect: {
                    disConnect();
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_disConnectA2dp: {
                    disConnectA2dp();
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_getConnectDeviceName: {
                    String result = getConnectDeviceName();
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                case TRANSACTION_getConnectDeviceAddress: {
                    String result = getConnectDeviceAddress();
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                case TRANSACTION_supportInbandRing: {
                    boolean result = supportInbandRing();
                    reply.writeNoException();
                    reply.writeInt(result ? 1 : 0);
                    return true;
                }
                case TRANSACTION_acceptConnect: {
                    int profile = data.readInt();
                    acceptConnect(profile);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_rejectConnect: {
                    int profile = data.readInt();
                    rejectConnect(profile);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_getPairedDevices: {
                    List<BluetoothDevice> result = getPairedDevices();
                    reply.writeNoException();
                    reply.writeTypedList(result);
                    return true;
                }
                case TRANSACTION_getDeviceName: {
                    String result = getDeviceName();
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                case TRANSACTION_getBatteryCharge: {
                    int result = getBatteryCharge();
                    reply.writeNoException();
                    reply.writeInt(result);
                    return true;
                }
                case TRANSACTION_getSignalValue: {
                    int result = getSignalValue();
                    reply.writeNoException();
                    reply.writeInt(result);
                    return true;
                }
                case TRANSACTION_setAutoPowerOn: {
                    boolean autoPowerOn = (data.readInt() != 0);
                    setAutoPowerOn(autoPowerOn);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_getSdkVersion: {
                    String result = getSdkVersion();
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                case TRANSACTION_supportMusic: {
                    boolean result = supportMusic();
                    reply.writeNoException();
                    reply.writeInt(result ? 1 : 0);
                    return true;
                }
                case TRANSACTION_supportPhone: {
                    boolean result = supportPhone();
                    reply.writeNoException();
                    reply.writeInt(result ? 1 : 0);
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static class Proxy implements IBluetoothManager {
            private final IBinder mRemote;

            Proxy(IBinder remote) {
                mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return mRemote;
            }

            public String getInterfaceDescriptor() {
                return DESCRIPTOR;
            }

            @Override
            public boolean registCallback(IBluetoothCallback callback) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                boolean _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeStrongBinder(callback != null ? callback.asBinder() : null);
                    mRemote.transact(TRANSACTION_registCallback, _data, _reply, 0);
                    _reply.readException();
                    _result = (0 != _reply.readInt());
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public boolean unregistCallback(IBluetoothCallback callback) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                boolean _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeStrongBinder(callback != null ? callback.asBinder() : null);
                    mRemote.transact(TRANSACTION_unregistCallback, _data, _reply, 0);
                    _reply.readException();
                    _result = (0 != _reply.readInt());
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public void unBindService() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_unBindService, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void setBtName(String name) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(name);
                    mRemote.transact(TRANSACTION_setBtName, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void btPowerOn() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_btPowerOn, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void btPowerOff() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_btPowerOff, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public int getPowerStatus() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_getPowerStatus, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public int search(boolean start) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt(start ? 1 : 0);
                    mRemote.transact(TRANSACTION_search, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public int stopSearch() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_stopSearch, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public boolean isSearching() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                boolean _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_isSearching, _data, _reply, 0);
                    _reply.readException();
                    _result = (0 != _reply.readInt());
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public int pairDevice(String address, int type) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(address);
                    _data.writeInt(type);
                    mRemote.transact(TRANSACTION_pairDevice, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public void acceptPair(String address, boolean accept) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(address);
                    _data.writeInt(accept ? 1 : 0);
                    mRemote.transact(TRANSACTION_acceptPair, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void rejectPair(String address, boolean reject) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(address);
                    _data.writeInt(reject ? 1 : 0);
                    mRemote.transact(TRANSACTION_rejectPair, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public boolean isParing() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                boolean _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_isParing, _data, _reply, 0);
                    _reply.readException();
                    _result = (0 != _reply.readInt());
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public int removePair(String address) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(address);
                    mRemote.transact(TRANSACTION_removePair, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public void connect(String address, String uuid) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(address);
                    _data.writeString(uuid);
                    mRemote.transact(TRANSACTION_connect, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void connectHfp(String address) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(address);
                    mRemote.transact(TRANSACTION_connectHfp, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void connectA2dp(String address) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(address);
                    mRemote.transact(TRANSACTION_connectA2dp, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public int getConnectStatus(int profile) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt(profile);
                    mRemote.transact(TRANSACTION_getConnectStatus, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public void disConnect() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_disConnect, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void disConnectA2dp() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_disConnectA2dp, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public String getConnectDeviceName() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                String _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_getConnectDeviceName, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readString();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public String getConnectDeviceAddress() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                String _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_getConnectDeviceAddress, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readString();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public boolean supportInbandRing() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                boolean _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_supportInbandRing, _data, _reply, 0);
                    _reply.readException();
                    _result = (0 != _reply.readInt());
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public void acceptConnect(int profile) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt(profile);
                    mRemote.transact(TRANSACTION_acceptConnect, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void rejectConnect(int profile) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt(profile);
                    mRemote.transact(TRANSACTION_rejectConnect, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public List<BluetoothDevice> getPairedDevices() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                List<BluetoothDevice> _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_getPairedDevices, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.createTypedArrayList(BluetoothDevice.CREATOR);
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public String getDeviceName() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                String _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_getDeviceName, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readString();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public int getBatteryCharge() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_getBatteryCharge, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public int getSignalValue() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_getSignalValue, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public void setAutoPowerOn(boolean autoPowerOn) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt(autoPowerOn ? 1 : 0);
                    mRemote.transact(TRANSACTION_setAutoPowerOn, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public String getSdkVersion() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                String _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_getSdkVersion, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readString();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public boolean supportMusic() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                boolean _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_supportMusic, _data, _reply, 0);
                    _reply.readException();
                    _result = (0 != _reply.readInt());
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public boolean supportPhone() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                boolean _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_supportPhone, _data, _reply, 0);
                    _reply.readException();
                    _result = (0 != _reply.readInt());
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }
        }
    }
}
