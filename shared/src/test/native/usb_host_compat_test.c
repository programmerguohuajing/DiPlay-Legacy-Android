/* Build with an Android NDK clang target, then run the executable with adb shell. */
#include <jni.h>
#include <errno.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <linux/usbdevice_fs.h>
#include <assert.h>
#include <stdio.h>

static int mock_ioctl(int fd, unsigned long request, void *argument);
#define ioctl mock_ioctl
#include "../../main/jni/usb_host_compat.c"
#undef ioctl

static int initial_error;
static int final_error;
static int config_calls;
static int disconnected;
static int restored;
static int userspace_owner;
static int disconnect_error;
static int reset_calls;
static int reset_error;

static void reset(void) {
    initial_error = final_error = config_calls = disconnected = restored = 0;
    userspace_owner = disconnect_error = 0;
    reset_calls = reset_error = 0;
}

static int mock_ioctl(int fd, unsigned long request, void *argument) {
    assert(fd == 42);
    if (request == USBDEVFS_SETCONFIGURATION) {
        assert(*(unsigned int *) argument == 6);
        errno = config_calls++ == 0 ? initial_error : final_error;
        return errno == 0 ? 0 : -1;
    }
    if (request == USBDEVFS_GETDRIVER) {
        struct usbdevfs_getdriver *driver = argument;
        if (driver->interface > 0) { errno = ENODATA; return -1; }
        strcpy(driver->driver, userspace_owner ? "usbfs" : "ipheth");
        return 0;
    }
    if (request == USBDEVFS_IOCTL) {
        struct usbdevfs_ioctl *operation = argument;
        assert(operation->ifno == 0);
        if (operation->ioctl_code == USBDEVFS_DISCONNECT) {
            assert(!userspace_owner);
            if (disconnect_error) { errno = disconnect_error; return -1; }
            disconnected++;
            return 0;
        }
        if (operation->ioctl_code == USBDEVFS_CONNECT) { restored++; return 0; }
    }
    if (request == USBDEVFS_RESET) {
        reset_calls++;
        if (reset_error) { errno = reset_error; return -1; }
        return 0;
    }
    assert(0 && "Unexpected ioctl");
    return -1;
}

static int configure(void) {
    return Java_com_shilapi_xcertplay_transport_LegacyUsbHostNative_setConfiguration(NULL, NULL, 42, 6);
}

int main(void) {
    reset();
    assert(configure() == 0 && config_calls == 1 && disconnected == 0);
    reset();
    initial_error = EACCES;
    assert(configure() == EACCES && config_calls == 1 && disconnected == 0);
    reset();
    initial_error = EBUSY;
    assert(configure() == 0 && config_calls == 2 && disconnected == 1 && restored == 0);
    reset();
    initial_error = EBUSY;
    final_error = EIO;
    assert(configure() == EIO && disconnected == 1 && restored == 1);
    reset();
    initial_error = EBUSY;
    userspace_owner = 1;
    assert(configure() == EBUSY && config_calls == 1 && disconnected == 0);
    reset();
    initial_error = EBUSY;
    disconnect_error = EPERM;
    assert(configure() == EPERM && config_calls == 1 && disconnected == 0);
    reset();
    assert(Java_com_shilapi_xcertplay_transport_LegacyUsbHostNative_resetDevice(NULL, NULL, 42) == 0 &&
           reset_calls == 1);
    reset();
    reset_error = ENODEV;
    assert(Java_com_shilapi_xcertplay_transport_LegacyUsbHostNative_resetDevice(NULL, NULL, 42) == ENODEV &&
           reset_calls == 1);
    assert(Java_com_shilapi_xcertplay_transport_LegacyUsbHostNative_resetDevice(NULL, NULL, -1) == EINVAL &&
           reset_calls == 0);
    puts("PASS: nine native USB configuration/driver/reset recovery scenarios (mocked ioctls)");
    return 0;
}
