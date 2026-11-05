"""
Native Windows Parsec Virtual Display Adapter (Root\\Parsec\\VDA) Controller.
Dynamically plugs in and maintains a real 3rd extended Windows monitor at runtime
using the installed Parsec Indirect Display Driver (IDD) IOCTL interface.
"""

import ctypes
import ctypes.wintypes as wintypes
import struct
import threading
import time
from typing import Optional

import mss

setupapi = ctypes.windll.setupapi
kernel32 = ctypes.windll.kernel32


class GUID(ctypes.Structure):
    _fields_ = [
        ("Data1", wintypes.DWORD),
        ("Data2", wintypes.WORD),
        ("Data3", wintypes.WORD),
        ("Data4", ctypes.c_ubyte * 8),
    ]


class SP_DEVICE_INTERFACE_DATA(ctypes.Structure):
    _fields_ = [
        ("cbSize", wintypes.DWORD),
        ("InterfaceClassGuid", GUID),
        ("Flags", wintypes.DWORD),
        ("Reserved", ctypes.POINTER(ctypes.c_ulong)),
    ]


# Parsec Virtual Display Adapter Interface GUID: {00b41627-04c4-429e-a26e-0265cf50c8fa}
VDA_INTERFACE_GUID = GUID(
    0x00B41627,
    0x04C4,
    0x429E,
    (ctypes.c_ubyte * 8)(0xA2, 0x6E, 0x02, 0x65, 0xCF, 0x50, 0xC8, 0xFA),
)

DIGCF_PRESENT = 0x00000002
DIGCF_DEVICEINTERFACE = 0x00000010
GENERIC_READ = 0x80000000
GENERIC_WRITE = 0x40000000
FILE_SHARE_READ = 0x00000001
FILE_SHARE_WRITE = 0x00000002
OPEN_EXISTING = 3
FILE_ATTRIBUTE_NORMAL = 0x00000080
FILE_FLAG_OVERLAPPED = 0x40000000
INVALID_HANDLE_VALUE = ctypes.c_void_p(-1).value

# Exact VddCtlCode definitions from parsec-vdd.h
VDD_IOCTL_ADD = 0x0022E004
VDD_IOCTL_REMOVE = 0x0022A008
VDD_IOCTL_UPDATE = 0x0022A00C
VDD_IOCTL_VERSION = 0x0022E010


class OVERLAPPED(ctypes.Structure):
    _fields_ = [
        ("Internal", ctypes.c_void_p),
        ("InternalHigh", ctypes.c_void_p),
        ("Offset", wintypes.DWORD),
        ("OffsetHigh", wintypes.DWORD),
        ("hEvent", wintypes.HANDLE),
    ]


def find_parsec_vda_device_path() -> Optional[str]:
    setupapi.SetupDiGetClassDevsW.restype = ctypes.c_void_p
    setupapi.SetupDiGetClassDevsW.argtypes = [
        ctypes.POINTER(GUID),
        wintypes.LPCWSTR,
        wintypes.HWND,
        wintypes.DWORD,
    ]
    h_dev_info = setupapi.SetupDiGetClassDevsW(
        ctypes.byref(VDA_INTERFACE_GUID),
        None,
        None,
        DIGCF_PRESENT | DIGCF_DEVICEINTERFACE,
    )
    if not h_dev_info or h_dev_info == INVALID_HANDLE_VALUE:
        return None

    try:
        dev_interface_data = SP_DEVICE_INTERFACE_DATA()
        dev_interface_data.cbSize = ctypes.sizeof(SP_DEVICE_INTERFACE_DATA)

        setupapi.SetupDiEnumDeviceInterfaces.argtypes = [
            ctypes.c_void_p,
            ctypes.c_void_p,
            ctypes.POINTER(GUID),
            wintypes.DWORD,
            ctypes.POINTER(SP_DEVICE_INTERFACE_DATA),
        ]
        setupapi.SetupDiEnumDeviceInterfaces.restype = wintypes.BOOL

        if not setupapi.SetupDiEnumDeviceInterfaces(
            h_dev_info, None, ctypes.byref(VDA_INTERFACE_GUID), 0, ctypes.byref(dev_interface_data)
        ):
            return None

        required_size = wintypes.DWORD(0)
        setupapi.SetupDiGetDeviceInterfaceDetailW.argtypes = [
            ctypes.c_void_p,
            ctypes.POINTER(SP_DEVICE_INTERFACE_DATA),
            ctypes.c_void_p,
            wintypes.DWORD,
            ctypes.POINTER(wintypes.DWORD),
            ctypes.c_void_p,
        ]
        setupapi.SetupDiGetDeviceInterfaceDetailW(
            h_dev_info,
            ctypes.byref(dev_interface_data),
            None,
            0,
            ctypes.byref(required_size),
            None,
        )
        if required_size.value == 0:
            return None

        buf = ctypes.create_string_buffer(required_size.value)
        struct.pack_into("I", buf, 0, 8 if ctypes.sizeof(ctypes.c_void_p) == 8 else 6)

        if not setupapi.SetupDiGetDeviceInterfaceDetailW(
            h_dev_info,
            ctypes.byref(dev_interface_data),
            buf,
            required_size.value,
            None,
            None,
        ):
            return None

        device_path = ctypes.wstring_at(ctypes.addressof(buf) + 4)
        return device_path
    finally:
        setupapi.SetupDiDestroyDeviceInfoList.argtypes = [ctypes.c_void_p]
        setupapi.SetupDiDestroyDeviceInfoList(h_dev_info)


class ParsecVirtualDisplayManager:
    """
    Manages a live 3rd virtual monitor via Parsec Virtual Display Adapter.
    Sends periodic keepalive pulses (VDD_IOCTL_UPDATE) every 80ms as required by the IDD driver
    so the display stays plugged in while the VR server is running, and cleanly
    unplugs when toggled off or closed.
    """

    def __init__(self):
        self.handle: Optional[int] = None
        self.display_index: int = -1
        self.active: bool = False
        self._stop_event = threading.Event()
        self._keepalive_thread: Optional[threading.Thread] = None
        self.device_path: Optional[str] = find_parsec_vda_device_path()

    def _vdd_io_control(self, code: int, in_bytes: Optional[bytes] = None) -> int:
        if not self.handle or self.handle == INVALID_HANDLE_VALUE:
            return -1

        # Parsec VDD strictly expects a 32-byte InBuffer for all IOCTL codes
        in_buf = ctypes.create_string_buffer(32)
        if in_bytes:
            n = min(len(in_bytes), 32)
            ctypes.memmove(in_buf, in_bytes, n)

        out_val = wintypes.DWORD(0)
        bytes_returned = wintypes.DWORD(0)

        overlapped = OVERLAPPED()
        overlapped.hEvent = kernel32.CreateEventW(None, False, False, None)
        try:
            kernel32.DeviceIoControl(
                ctypes.c_void_p(self.handle),
                code,
                in_buf,
                32,
                ctypes.byref(out_val),
                ctypes.sizeof(out_val),
                None,
                ctypes.byref(overlapped),
            )
            kernel32.GetOverlappedResult(
                ctypes.c_void_p(self.handle),
                ctypes.byref(overlapped),
                ctypes.byref(bytes_returned),
                True,
            )
            return out_val.value
        finally:
            if overlapped.hEvent:
                kernel32.CloseHandle(overlapped.hEvent)

    def create_third_monitor(self) -> tuple[bool, str]:
        if self.active and self.display_index >= 0:
            return True, f"Virtual 3rd Monitor already active (Index #{self.display_index})"

        self.device_path = find_parsec_vda_device_path()
        if not self.device_path:
            return False, "Parsec Virtual Display Adapter device interface not found"

        kernel32.CreateFileW.restype = ctypes.c_void_p
        kernel32.CreateFileW.argtypes = [
            wintypes.LPCWSTR,
            wintypes.DWORD,
            wintypes.DWORD,
            ctypes.c_void_p,
            wintypes.DWORD,
            wintypes.DWORD,
            wintypes.HANDLE,
        ]
        h = kernel32.CreateFileW(
            self.device_path,
            GENERIC_READ | GENERIC_WRITE,
            FILE_SHARE_READ | FILE_SHARE_WRITE,
            None,
            OPEN_EXISTING,
            FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OVERLAPPED,
            None,
        )
        if not h or h == INVALID_HANDLE_VALUE:
            return False, "Failed to open Parsec VDA handle"

        self.handle = h
        ver = self._vdd_io_control(VDD_IOCTL_VERSION, None)
        idx = self._vdd_io_control(VDD_IOCTL_ADD, None)
        if idx < 0:
            kernel32.CloseHandle(ctypes.c_void_p(self.handle))
            self.handle = None
            return False, "VDD_IOCTL_ADD failed on Parsec VDA"

        self.display_index = idx
        self.active = True
        self._stop_event.clear()

        # Send immediate update and start background heartbeat loop (every 80ms)
        self._vdd_io_control(VDD_IOCTL_UPDATE, None)
        self._keepalive_thread = threading.Thread(target=self._heartbeat_loop, daemon=True)
        self._keepalive_thread.start()

        # Wait up to 3 seconds for Windows Display Manager to attach the 3rd display
        for _ in range(20):
            time.sleep(0.15)
            with mss.MSS() as sct:
                if len(sct.monitors) >= 4:
                    break

        return True, f"Plugged in Virtual 3rd Monitor (Slot #{self.display_index}, Driver v{ver})"

    def _heartbeat_loop(self):
        while not self._stop_event.is_set() and self.active:
            self._vdd_io_control(VDD_IOCTL_UPDATE, None)
            time.sleep(0.08)

    def remove_third_monitor(self) -> tuple[bool, str]:
        if not self.active:
            return True, "Virtual 3rd Monitor already disconnected"

        self.active = False
        self._stop_event.set()
        if self._keepalive_thread and self._keepalive_thread.is_alive():
            self._keepalive_thread.join(timeout=0.5)

        if self.handle and self.handle != INVALID_HANDLE_VALUE:
            # 16-bit Big-Endian index as required by VddRemoveDisplay
            be_index = struct.pack(">H", self.display_index & 0xFFFF)
            self._vdd_io_control(VDD_IOCTL_REMOVE, be_index)
            self._vdd_io_control(VDD_IOCTL_UPDATE, None)
            kernel32.CloseHandle(ctypes.c_void_p(self.handle))
            self.handle = None

        self.display_index = -1
        return True, "Unplugged Virtual 3rd Monitor"


if __name__ == "__main__":
    vdd = ParsecVirtualDisplayManager()
    print("Device Path:", vdd.device_path)
    ok, msg = vdd.create_third_monitor()
    print("Create:", ok, msg)
    with mss.MSS() as sct:
        print("Monitors count:", len(sct.monitors) - 1, sct.monitors)
    time.sleep(2)
    ok2, msg2 = vdd.remove_third_monitor()
    print("Remove:", ok2, msg2)
