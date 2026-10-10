package com.anwsdk.service;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

public interface IAnwSPPDataCallBack extends IInterface {
    void SPPDataIND(int nIndex, byte[] data, int dataLength) throws RemoteException;

    abstract class Stub extends Binder implements IAnwSPPDataCallBack {
        private static final String DESCRIPTOR = "com.anwsdk.service.IAnwSPPDataCallBack";
        static final int TRANSACTION_SPPDataIND = 1;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IAnwSPPDataCallBack asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IAnwSPPDataCallBack) {
                return (IAnwSPPDataCallBack) iin;
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
            if (code == TRANSACTION_SPPDataIND) {
                int _arg0 = data.readInt();
                byte[] _arg1 = data.createByteArray();
                int _arg2 = data.readInt();
                SPPDataIND(_arg0, _arg1, _arg2);
                reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static class Proxy implements IAnwSPPDataCallBack {
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
            public void SPPDataIND(int nIndex, byte[] data, int dataLength) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt(nIndex);
                    _data.writeByteArray(data);
                    _data.writeInt(dataLength);
                    mRemote.transact(TRANSACTION_SPPDataIND, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }
        }
    }
}
