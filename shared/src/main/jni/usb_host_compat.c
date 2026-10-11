#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <linux/usbdevice_fs.h>

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_network_LegacyTunBlocking_setBlocking(
        JNIEnv *env, jobject self, jint fd) {
    (void) env;
    (void) self;
    int flags = fcntl(fd, F_GETFL);
    if (flags < 0) return errno;
    if (fcntl(fd, F_SETFL, flags & ~O_NONBLOCK) < 0) return errno;
    return 0;
}

/* Returns 0 when the interface is claimed, otherwise the final errno. */
JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_LegacyUsbHostNative_forceClaim(
        JNIEnv *env, jobject self, jint file_descriptor, jint interface_id) {
    (void) env;
    (void) self;

    if (file_descriptor < 0 || interface_id < 0) return EINVAL;

#ifdef USBDEVFS_DISCONNECT_CLAIM
    struct usbdevfs_disconnect_claim disconnect_claim;
    memset(&disconnect_claim, 0, sizeof(disconnect_claim));
    disconnect_claim.interface = (unsigned int) interface_id;
    disconnect_claim.flags = 0;
    if (ioctl(file_descriptor, USBDEVFS_DISCONNECT_CLAIM, &disconnect_claim) == 0) {
        return 0;
    }
#endif

#ifdef USBDEVFS_DISCONNECT
    struct usbdevfs_ioctl disconnect;
    memset(&disconnect, 0, sizeof(disconnect));
    disconnect.ifno = interface_id;
    disconnect.ioctl_code = USBDEVFS_DISCONNECT;
    disconnect.data = NULL;
    (void) ioctl(file_descriptor, USBDEVFS_IOCTL, &disconnect);
#endif

    unsigned int iface = (unsigned int) interface_id;
    if (ioctl(file_descriptor, USBDEVFS_CLAIMINTERFACE, &iface) == 0) {
        return 0;
    }
    return errno != 0 ? errno : EIO;
}


JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_LegacyUsbHostNative_setInterface(
        JNIEnv *env, jobject self, jint file_descriptor, jint interface_id, jint alternate_setting) {
    (void) env;
    (void) self;

    if (file_descriptor < 0 || interface_id < 0 || alternate_setting < 0) return EINVAL;

    struct usbdevfs_setinterface set_interface;
    memset(&set_interface, 0, sizeof(set_interface));
    set_interface.interface = (unsigned int) interface_id;
    set_interface.altsetting = (unsigned int) alternate_setting;

    if (ioctl(file_descriptor, USBDEVFS_SETINTERFACE, &set_interface) == 0) {
        return 0;
    }
    return errno != 0 ? errno : EIO;
}

/* Returns 0 when the port reset completed, otherwise the final errno. A reset makes
 * the device re-enumerate exactly like a physical unplug/replug, unwedging iPhones
 * that stopped answering control transfers (usbfs errno EPROTO). */
JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_LegacyUsbHostNative_resetDevice(
        JNIEnv *env, jobject self, jint file_descriptor) {
    (void) env;
    (void) self;

    if (file_descriptor < 0) return EINVAL;

    if (ioctl(file_descriptor, USBDEVFS_RESET) == 0) {
        return 0;
    }
    return errno != 0 ? errno : EIO;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_LegacyUsbHostNative_setConfiguration(
        JNIEnv *env, jobject self, jint file_descriptor, jint configuration_id) {
    (void) env;
    (void) self;

    if (file_descriptor < 0 || configuration_id < 0) return EINVAL;

    unsigned int configuration = (unsigned int) configuration_id;
    if (ioctl(file_descriptor, USBDEVFS_SETCONFIGURATION, &configuration) == 0) {
        return 0;
    }
    int result = errno != 0 ? errno : EIO;
    if (result != EBUSY) return result;

    /* SETCONFIGURATION refuses bound interfaces. Only detach kernel drivers on this
     * authorized device; never disconnect another userspace (usbfs) owner. */
    unsigned int detached[256];
    unsigned int detached_count = 0;
    for (unsigned int iface = 0; iface < 256; iface++) {
        struct usbdevfs_getdriver driver;
        memset(&driver, 0, sizeof(driver));
        driver.interface = iface;
        if (ioctl(file_descriptor, USBDEVFS_GETDRIVER, &driver) != 0) continue;
        if (strncmp(driver.driver, "usbfs", sizeof(driver.driver)) == 0) {
            result = EBUSY;
            goto restore_drivers;
        }
        struct usbdevfs_ioctl disconnect;
        memset(&disconnect, 0, sizeof(disconnect));
        disconnect.ifno = (int) iface;
        disconnect.ioctl_code = USBDEVFS_DISCONNECT;
        if (ioctl(file_descriptor, USBDEVFS_IOCTL, &disconnect) != 0) {
            result = errno != 0 ? errno : EIO;
            goto restore_drivers;
        }
        detached[detached_count++] = iface;
    }
    if (ioctl(file_descriptor, USBDEVFS_SETCONFIGURATION, &configuration) == 0) return 0;
    result = errno != 0 ? errno : EIO;

restore_drivers:
    for (unsigned int i = 0; i < detached_count; i++) {
        struct usbdevfs_ioctl reconnect;
        memset(&reconnect, 0, sizeof(reconnect));
        reconnect.ifno = (int) detached[i];
        reconnect.ioctl_code = USBDEVFS_CONNECT;
        (void) ioctl(file_descriptor, USBDEVFS_IOCTL, &reconnect);
    }
    return result;
}
