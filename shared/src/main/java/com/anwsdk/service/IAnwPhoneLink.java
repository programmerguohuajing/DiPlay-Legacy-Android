package com.anwsdk.service;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

public interface IAnwPhoneLink extends IInterface {

    int ANWBT_SocketInit() throws RemoteException;

    int ANWBT_SocketDenit() throws RemoteException;

    int ANWBT_SocketConnect(int nType, String address, byte[] uuid, int channel, long nMTU, long Flag, int[] nIndex) throws RemoteException;

    int ANWBT_SocketDisconnect(int nindex) throws RemoteException;

    int ANWBT_SocketWrite(int nindex, byte[] b, int datasize, int[] nWritten) throws RemoteException;

    void ANWBT_RegistrySocketDataCallback(IAnwSocketDataCallBack cb) throws RemoteException;

    void ANWBT_UnRegistrySocketDataCallback(IAnwSocketDataCallBack cb) throws RemoteException;

    boolean ANWBT_GetSocketInitStatus() throws RemoteException;

    int ANWBT_SPPInit(byte[] b) throws RemoteException;

    int ANWBT_SPPDenit() throws RemoteException;

    int ANWBT_SPPConnect(String address, byte[] b, int[] nIndex) throws RemoteException;

    int ANWBT_SPPDisconnect(int nindex) throws RemoteException;

    int ANWBT_SPPWrite(int nindex, byte[] b, int datasize, int[] nWritten) throws RemoteException;

    boolean ANWBT_GetSPPInitStatus() throws RemoteException;

    void ANWBT_RegistrySPPDataCallback(IAnwSPPDataCallBack cb) throws RemoteException;

    void ANWBT_UnRegistrySPPDataCallback(IAnwSPPDataCallBack cb) throws RemoteException;

    abstract class Stub extends Binder implements IAnwPhoneLink {
        private static final String DESCRIPTOR = "com.anwsdk.service.IAnwPhoneLink";

        static final int TRANSACTION_ANWBT_SPPInit = 62;
        static final int TRANSACTION_ANWBT_SPPDenit = 63;
        static final int TRANSACTION_ANWBT_SPPConnect = 64;
        static final int TRANSACTION_ANWBT_SPPDisconnect = 65;
        static final int TRANSACTION_ANWBT_SPPWrite = 66;
        static final int TRANSACTION_ANWBT_GetSPPInitStatus = 67;
        static final int TRANSACTION_ANWBT_RegistrySPPDataCallback = 68;
        static final int TRANSACTION_ANWBT_UnRegistrySPPDataCallback = 69;
        static final int TRANSACTION_ANWBT_SocketInit = 222;
        static final int TRANSACTION_ANWBT_SocketDenit = 224;
        static final int TRANSACTION_ANWBT_SocketConnect = 225;
        static final int TRANSACTION_ANWBT_SocketDisconnect = 226;
        static final int TRANSACTION_ANWBT_SocketWrite = 227;
        static final int TRANSACTION_ANWBT_RegistrySocketDataCallback = 231;
        static final int TRANSACTION_ANWBT_UnRegistrySocketDataCallback = 232;
        static final int TRANSACTION_ANWBT_GetSocketInitStatus = 233;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IAnwPhoneLink asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IAnwPhoneLink) {
                return (IAnwPhoneLink) iin;
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
                case TRANSACTION_ANWBT_SocketInit: {
                    int _result = ANWBT_SocketInit();
                    reply.writeNoException();
                    reply.writeInt(_result);
                    return true;
                }
                case TRANSACTION_ANWBT_SocketDenit: {
                    int _result = ANWBT_SocketDenit();
                    reply.writeNoException();
                    reply.writeInt(_result);
                    return true;
                }
                case TRANSACTION_ANWBT_SocketConnect: {
                    int _arg0 = data.readInt();
                    String _arg1 = data.readString();
                    byte[] _arg2 = data.createByteArray();
                    int _arg3 = data.readInt();
                    long _arg4 = data.readLong();
                    long _arg5 = data.readLong();
                    int[] _arg6;
                    int _arg6_len = data.readInt();
                    if (_arg6_len < 0) {
                        _arg6 = null;
                    } else {
                        _arg6 = new int[_arg6_len];
                    }
                    int _result = ANWBT_SocketConnect(_arg0, _arg1, _arg2, _arg3, _arg4, _arg5, _arg6);
                    reply.writeNoException();
                    reply.writeInt(_result);
                    reply.writeIntArray(_arg6);
                    return true;
                }
                case TRANSACTION_ANWBT_SocketDisconnect: {
                    int _arg0 = data.readInt();
                    int _result = ANWBT_SocketDisconnect(_arg0);
                    reply.writeNoException();
                    reply.writeInt(_result);
                    return true;
                }
                case TRANSACTION_ANWBT_SocketWrite: {
                    int _arg0 = data.readInt();
                    byte[] _arg1 = data.createByteArray();
                    int _arg2 = data.readInt();
                    int[] _arg3;
                    int _arg3_len = data.readInt();
                    if (_arg3_len < 0) {
                        _arg3 = null;
                    } else {
                        _arg3 = new int[_arg3_len];
                    }
                    int _result = ANWBT_SocketWrite(_arg0, _arg1, _arg2, _arg3);
                    reply.writeNoException();
                    reply.writeInt(_result);
                    reply.writeIntArray(_arg3);
                    return true;
                }
                case TRANSACTION_ANWBT_RegistrySocketDataCallback: {
                    IAnwSocketDataCallBack _arg0 = IAnwSocketDataCallBack.Stub.asInterface(data.readStrongBinder());
                    ANWBT_RegistrySocketDataCallback(_arg0);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_ANWBT_UnRegistrySocketDataCallback: {
                    IAnwSocketDataCallBack _arg0 = IAnwSocketDataCallBack.Stub.asInterface(data.readStrongBinder());
                    ANWBT_UnRegistrySocketDataCallback(_arg0);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_ANWBT_GetSocketInitStatus: {
                    boolean _result = ANWBT_GetSocketInitStatus();
                    reply.writeNoException();
                    reply.writeInt(_result ? 1 : 0);
                    return true;
                }
                case TRANSACTION_ANWBT_SPPInit: {
                    byte[] _arg0 = data.createByteArray();
                    int _result = ANWBT_SPPInit(_arg0);
                    reply.writeNoException();
                    reply.writeInt(_result);
                    return true;
                }
                case TRANSACTION_ANWBT_SPPDenit: {
                    int _result = ANWBT_SPPDenit();
                    reply.writeNoException();
                    reply.writeInt(_result);
                    return true;
                }
                case TRANSACTION_ANWBT_SPPConnect: {
                    String _arg0 = data.readString();
                    byte[] _arg1 = data.createByteArray();
                    int[] _arg2;
                    int _arg2_len = data.readInt();
                    if (_arg2_len < 0) {
                        _arg2 = null;
                    } else {
                        _arg2 = new int[_arg2_len];
                    }
                    int _result = ANWBT_SPPConnect(_arg0, _arg1, _arg2);
                    reply.writeNoException();
                    reply.writeInt(_result);
                    reply.writeIntArray(_arg2);
                    return true;
                }
                case TRANSACTION_ANWBT_SPPDisconnect: {
                    int _arg0 = data.readInt();
                    int _result = ANWBT_SPPDisconnect(_arg0);
                    reply.writeNoException();
                    reply.writeInt(_result);
                    return true;
                }
                case TRANSACTION_ANWBT_SPPWrite: {
                    int _arg0 = data.readInt();
                    byte[] _arg1 = data.createByteArray();
                    int _arg2 = data.readInt();
                    int[] _arg3;
                    int _arg3_len = data.readInt();
                    if (_arg3_len < 0) {
                        _arg3 = null;
                    } else {
                        _arg3 = new int[_arg3_len];
                    }
                    int _result = ANWBT_SPPWrite(_arg0, _arg1, _arg2, _arg3);
                    reply.writeNoException();
                    reply.writeInt(_result);
                    reply.writeIntArray(_arg3);
                    return true;
                }
                case TRANSACTION_ANWBT_GetSPPInitStatus: {
                    boolean _result = ANWBT_GetSPPInitStatus();
                    reply.writeNoException();
                    reply.writeInt(_result ? 1 : 0);
                    return true;
                }
                case TRANSACTION_ANWBT_RegistrySPPDataCallback: {
                    IAnwSPPDataCallBack _arg0 = IAnwSPPDataCallBack.Stub.asInterface(data.readStrongBinder());
                    ANWBT_RegistrySPPDataCallback(_arg0);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_ANWBT_UnRegistrySPPDataCallback: {
                    IAnwSPPDataCallBack _arg0 = IAnwSPPDataCallBack.Stub.asInterface(data.readStrongBinder());
                    ANWBT_UnRegistrySPPDataCallback(_arg0);
                    reply.writeNoException();
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static class Proxy implements IAnwPhoneLink {
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
            public int ANWBT_SocketInit() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_ANWBT_SocketInit, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public int ANWBT_SocketDenit() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_ANWBT_SocketDenit, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public int ANWBT_SocketConnect(int nType, String address, byte[] uuid, int channel, long nMTU, long Flag, int[] nIndex) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt(nType);
                    _data.writeString(address);
                    _data.writeByteArray(uuid);
                    _data.writeInt(channel);
                    _data.writeLong(nMTU);
                    _data.writeLong(Flag);
                    if (nIndex == null) {
                        _data.writeInt(-1);
                    } else {
                        _data.writeInt(nIndex.length);
                    }
                    mRemote.transact(TRANSACTION_ANWBT_SocketConnect, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                    if (nIndex != null) {
                        _reply.readIntArray(nIndex);
                    }
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public int ANWBT_SocketDisconnect(int nindex) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt(nindex);
                    mRemote.transact(TRANSACTION_ANWBT_SocketDisconnect, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public int ANWBT_SocketWrite(int nindex, byte[] b, int datasize, int[] nWritten) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt(nindex);
                    _data.writeByteArray(b);
                    _data.writeInt(datasize);
                    if (nWritten == null) {
                        _data.writeInt(-1);
                    } else {
                        _data.writeInt(nWritten.length);
                    }
                    mRemote.transact(TRANSACTION_ANWBT_SocketWrite, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                    if (nWritten != null) {
                        _reply.readIntArray(nWritten);
                    }
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public void ANWBT_RegistrySocketDataCallback(IAnwSocketDataCallBack cb) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeStrongBinder(cb != null ? cb.asBinder() : null);
                    mRemote.transact(TRANSACTION_ANWBT_RegistrySocketDataCallback, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void ANWBT_UnRegistrySocketDataCallback(IAnwSocketDataCallBack cb) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeStrongBinder(cb != null ? cb.asBinder() : null);
                    mRemote.transact(TRANSACTION_ANWBT_UnRegistrySocketDataCallback, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public boolean ANWBT_GetSocketInitStatus() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                boolean _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_ANWBT_GetSocketInitStatus, _data, _reply, 0);
                    _reply.readException();
                    _result = (0 != _reply.readInt());
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public int ANWBT_SPPInit(byte[] b) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeByteArray(b);
                    mRemote.transact(TRANSACTION_ANWBT_SPPInit, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public int ANWBT_SPPDenit() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_ANWBT_SPPDenit, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public int ANWBT_SPPConnect(String address, byte[] b, int[] nIndex) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(address);
                    _data.writeByteArray(b);
                    if (nIndex == null) {
                        _data.writeInt(-1);
                    } else {
                        _data.writeInt(nIndex.length);
                    }
                    mRemote.transact(TRANSACTION_ANWBT_SPPConnect, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                    if (nIndex != null) {
                        _reply.readIntArray(nIndex);
                    }
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public int ANWBT_SPPDisconnect(int nindex) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt(nindex);
                    mRemote.transact(TRANSACTION_ANWBT_SPPDisconnect, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public int ANWBT_SPPWrite(int nindex, byte[] b, int datasize, int[] nWritten) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt(nindex);
                    _data.writeByteArray(b);
                    _data.writeInt(datasize);
                    if (nWritten == null) {
                        _data.writeInt(-1);
                    } else {
                        _data.writeInt(nWritten.length);
                    }
                    mRemote.transact(TRANSACTION_ANWBT_SPPWrite, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                    if (nWritten != null) {
                        _reply.readIntArray(nWritten);
                    }
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public boolean ANWBT_GetSPPInitStatus() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                boolean _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_ANWBT_GetSPPInitStatus, _data, _reply, 0);
                    _reply.readException();
                    _result = (0 != _reply.readInt());
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public void ANWBT_RegistrySPPDataCallback(IAnwSPPDataCallBack cb) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeStrongBinder(cb != null ? cb.asBinder() : null);
                    mRemote.transact(TRANSACTION_ANWBT_RegistrySPPDataCallback, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void ANWBT_UnRegistrySPPDataCallback(IAnwSPPDataCallBack cb) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeStrongBinder(cb != null ? cb.asBinder() : null);
                    mRemote.transact(TRANSACTION_ANWBT_UnRegistrySPPDataCallback, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }
        }
    }
}
