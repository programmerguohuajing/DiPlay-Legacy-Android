package com.hsae.bluetoothsdk.bean;

import android.os.Parcel;
import android.os.Parcelable;

public class BluetoothDevice implements Parcelable {
    public static final int STATUS_UNPAIRED = 0;
    public static final int STATUS_PAIRING = 1;
    public static final int STATUS_PAIRED = 2;
    public static final int STATUS_DISCONNECTED = 2;
    public static final int STATUS_UNPAIRING = 3;
    public static final int STATUS_CONNECTING = 4;
    public static final int STATUS_CONNECTED = 5;
    public static final int STATUS_DISCONNECTING = 6;

    private String name = "";
    private String mac = "";
    private int COD = 0;
    private int RSSI = 0;
    private int status = 0;

    public BluetoothDevice() {
    }

    public BluetoothDevice(String name, String mac, int COD, int RSSI, int status) {
        this.name = name != null ? name : "";
        this.mac = mac != null ? mac : "";
        this.COD = COD;
        this.RSSI = RSSI;
        this.status = status;
    }

    public BluetoothDevice(Parcel in) {
        readFromParcel(in);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMac() {
        return mac;
    }

    public void setMac(String mac) {
        this.mac = mac;
    }

    public int getCOD() {
        return COD;
    }

    public void setCOD(int COD) {
        this.COD = COD;
    }

    public int getRSSI() {
        return RSSI;
    }

    public void setRSSI(int RSSI) {
        this.RSSI = RSSI;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(name);
        dest.writeString(mac);
        dest.writeInt(COD);
        dest.writeInt(RSSI);
        dest.writeInt(status);
    }

    public void readFromParcel(Parcel in) {
        name = in.readString();
        mac = in.readString();
        COD = in.readInt();
        RSSI = in.readInt();
        status = in.readInt();
    }

    public static final Creator<BluetoothDevice> CREATOR = new Creator<BluetoothDevice>() {
        @Override
        public BluetoothDevice createFromParcel(Parcel in) {
            return new BluetoothDevice(in);
        }

        @Override
        public BluetoothDevice[] newArray(int size) {
            return new BluetoothDevice[size];
        }
    };
}
