import asyncio
import atexit
import ctypes
import io
import json
import math
import socket
import time
from pathlib import Path
from typing import Dict, List, Optional, Set

import mss
import psutil
import win32api
import win32con
import win32gui
import win32ui
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from PIL import Image, ImageDraw

from parsec_vdd import ParsecVirtualDisplayManager

# ==============================================================================
# WINDOWS NATIVE INPUT & CURSOR ENGINE (ctypes SendInput / SetCursorPos)
# ==============================================================================

user32 = ctypes.windll.user32
try:
    user32.SetProcessDPIAware()
except Exception:
    pass


def bind_input_desktop() -> None:
    """Attach the current thread to the active interactive Windows desktop (`Default`)."""
    try:
        hdesk = user32.OpenInputDesktop(0, False, 0x01FF)
        if hdesk:
            user32.SetThreadDesktop(hdesk)
            user32.CloseDesktop(hdesk)
    except Exception:
        pass


bind_input_desktop()


class POINT(ctypes.Structure):
    _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]


MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010


def get_cursor_pos() -> tuple[int, int]:
    pt = POINT()
    user32.GetCursorPos(ctypes.byref(pt))
    return pt.x, pt.y


def set_cursor_pos(x: int, y: int) -> None:
    user32.SetCursorPos(int(x), int(y))


def trigger_mouse_click(button: str = "left") -> None:
    if button == "right":
        user32.mouse_event(MOUSEEVENTF_RIGHTDOWN, 0, 0, 0, 0)
        user32.mouse_event(MOUSEEVENTF_RIGHTUP, 0, 0, 0, 0)
    elif button == "double":
        for _ in range(2):
            user32.mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)
            user32.mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)
            time.sleep(0.04)
    else:
        user32.mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)
        user32.mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)


# ==============================================================================
# 1-EURO ADAPTIVE FILTER + HEAD TRACKING & DWELL STATE MACHINE
# ==============================================================================


class LowPassFilter:
    def __init__(self, alpha: float, init_val: float = 0.0):
        self.y = init_val
        self.s = init_val
        self.initialized = False
        self.alpha = alpha

    def filter_with_alpha(self, value: float, alpha: float) -> float:
        self.alpha = max(0.001, min(1.0, alpha))
        if not self.initialized:
            self.y = value
            self.s = value
            self.initialized = True
            return value
        self.s = self.alpha * value + (1.0 - self.alpha) * self.s
        self.y = value
        return self.s


class OneEuroFilter:
    """Adaptive low-pass filter for ultra-low jitter during dwell and zero lag during fast head motion."""

    def __init__(self, min_cutoff: float = 1.2, beta: float = 0.04, d_cutoff: float = 1.0):
        self.min_cutoff = min_cutoff
        self.beta = beta
        self.d_cutoff = d_cutoff
        self.x_filter = LowPassFilter(1.0)
        self.dx_filter = LowPassFilter(1.0)
        self.last_time: Optional[float] = None

    def _alpha(self, cutoff: float, dt: float) -> float:
        tau = 1.0 / (2.0 * math.pi * cutoff)
        return 1.0 / (1.0 + tau / max(1e-5, dt))

    def reset(self):
        self.x_filter.initialized = False
        self.dx_filter.initialized = False
        self.last_time = None

    def __call__(self, x: float, t: Optional[float] = None) -> float:
        now = t if t is not None else time.perf_counter()
        if self.last_time is None:
            self.last_time = now
            return self.x_filter.filter_with_alpha(x, 1.0)
        dt = max(0.001, min(0.2, now - self.last_time))
        self.last_time = now

        dx = (x - self.x_filter.s) / dt if self.x_filter.initialized else 0.0
        edx = self.dx_filter.filter_with_alpha(dx, self._alpha(self.d_cutoff, dt))
        cutoff = self.min_cutoff + self.beta * abs(edx)
        return self.x_filter.filter_with_alpha(x, self._alpha(cutoff, dt))


def normalize_angle_deg(angle: float) -> float:
    """Wrap angle into [-180, 180] degrees."""
    while angle > 180.0:
        angle -= 360.0
    while angle < -180.0:
        angle += 360.0
    return angle


def get_monitors_info() -> List[dict]:
    result = []
    try:
        for idx, (h_mon, _hdc, _rect) in enumerate(win32api.EnumDisplayMonitors(), start=1):
            info = win32api.GetMonitorInfo(h_mon)
            dev_name = info["Device"]
            left, top, right, bottom = info["Monitor"]
            w = max(1, right - left)
            h = max(1, bottom - top)
            is_primary = bool(info.get("Flags", 0) & 1)

            adapter_str = ""
            try:
                dev_info = win32api.EnumDisplayDevices(dev_name, 0)
                adapter_str = dev_info.DeviceString
            except Exception:
                pass

            # Check if this display belongs to Parsec Virtual Display Adapter
            is_virtual_vdd = dev_name not in (r"\\.\DISPLAY1", r"\\.\DISPLAY2")
            try:
                for d_idx in range(32):
                    dd = win32api.EnumDisplayDevices(None, d_idx)
                    if dd.DeviceName == dev_name and "Parsec" in dd.DeviceString:
                        is_virtual_vdd = True
                        adapter_str = dd.DeviceString
                        break
            except Exception:
                pass

            if is_virtual_vdd:
                label = f"Monitor {idx} [VIRTUAL 3RD DISPLAY - VR HEADSET]: {w}x{h} ({dev_name})"
            elif is_primary:
                label = f"Monitor {idx} [PRIMARY]: {w}x{h} ({dev_name})"
            else:
                label = f"Monitor {idx}: {w}x{h} @ ({left},{top}) ({dev_name})"

            result.append(
                {
                    "index": idx,
                    "device": dev_name,
                    "left": left,
                    "top": top,
                    "width": w,
                    "height": h,
                    "is_primary": is_primary,
                    "is_virtual": is_virtual_vdd,
                    "label": label,
                }
            )
    except Exception:
        pass
    return result


class AetherEngineState:
    def __init__(self):
        # Virtual 3rd Monitor Manager (Parsec VDD)
        self.vdd_manager = ParsecVirtualDisplayManager()
        self.virtual_monitor_enabled: bool = True
        self.vdd_status_msg: str = "Initializing..."

        # Automatically plug in the 3rd virtual monitor on startup!
        ok, msg = self.vdd_manager.create_third_monitor()
        self.vdd_status_msg = msg

        # Discover monitors & default to Monitor 2 (Primary 3440x1440) so you immediately see your main screen in VR!
        self.monitors: List[dict] = get_monitors_info()
        primary_mon = next((m for m in self.monitors if m["is_primary"]), None)
        if primary_mon:
            self.active_monitor_index: int = primary_mon["index"]
        else:
            self.active_monitor_index: int = len(self.monitors) if self.monitors else 1

        # Cursor mapping mode: "active_monitor" maps head gaze directly to the selected VR monitor;
        # "all_monitors" maps across the entire multi-monitor desktop span.
        self.cursor_scope: str = "active_monitor"

        active_m = self.get_active_monitor_rect()
        self.screen_left: int = active_m["left"]
        self.screen_top: int = active_m["top"]
        self.screen_w: int = active_m["width"]
        self.screen_h: int = active_m["height"]

        # Configuration Parameters (Live-Tunable from PC Dashboard or Android HUD)
        # By default, continuous Dwell-Hold clicking is OFF so you can watch movies or read
        # without accidental clicks! Press [Alt + Space] on PC or Volume Down on phone to toggle,
        # or only arm dwell when hovering over interactive UI edges.
        self.mouse_control_enabled: bool = True
        self.dwell_enabled: bool = False
        self.dwell_auto_sleep_sec: float = 5.0  # Auto-disarm dwell after 5s of watching still
        self.dwell_time_ms: int = 850
        self.dwell_radius_px: float = 28.0
        self.dwell_cooldown_ms: int = 1200
        self.click_mode: str = "left"  # "left", "right", "double"

        # Sensitivity: degrees of head rotation for full screen width/height
        self.fov_yaw_deg: float = 38.0
        self.fov_pitch_deg: float = 24.0
        self.smoothing_min_cutoff: float = 1.15
        self.smoothing_beta: float = 0.045
        self.deadzone_deg: float = 0.12
        self.invert_yaw: bool = False
        self.invert_pitch: bool = False

        # Display & VR Optics Config (synced to Android Native Renderer)
        # Tuned for maximum text clarity & high-resolution VR readability:
        # - stream_scale = 1.0 (100% Native 1:1 Pixel Resolution, zero downscaling blur)
        # - stream_quality = 92 with 4:4:4 chroma (subsampling=0) for razor-sharp UI & text
        # - vr_screen_distance = 1.25m (fills 88% of each eye's horizontal viewport)
        # - vr_gaze_follow = True (smoothly pans virtual camera toward cursor when reading edges)
        # - vr_zoom_crop = False (optional 2.0x Retina Gaze Magnifier around cursor)
        self.stream_fps: int = 35
        self.stream_quality: int = 92
        self.stream_scale: float = 1.0
        self.vr_screen_distance: float = 1.25
        self.vr_screen_curvature: float = 0.28
        self.vr_ipd_offset: float = 0.062
        self.vr_gaze_follow: bool = True
        self.vr_zoom_crop: bool = False
        self.vr_zoom_factor: float = 1.85

        # Orientation & Calibration State
        self.raw_yaw: float = 0.0
        self.raw_pitch: float = 0.0
        self.raw_roll: float = 0.0
        self.quat: List[float] = [0.0, 0.0, 0.0, 1.0]

        self.center_yaw: float = 0.0
        self.center_pitch: float = 0.0
        self.center_roll: float = 0.0
        self.has_centered: bool = False

        self.rel_yaw: float = 0.0
        self.rel_pitch: float = 0.0
        self.rel_roll: float = 0.0

        # Cursor & Filter State
        self.filter_x = OneEuroFilter(self.smoothing_min_cutoff, self.smoothing_beta)
        self.filter_y = OneEuroFilter(self.smoothing_min_cutoff, self.smoothing_beta)
        self.norm_x: float = 0.5
        self.norm_y: float = 0.5
        self.cursor_x: int = self.screen_left + (self.screen_w // 2)
        self.cursor_y: int = self.screen_top + (self.screen_h // 2)
        self.last_filtered_yaw: float = 0.0
        self.last_filtered_pitch: float = 0.0

        # Dwell-to-Click State Machine
        self.dwell_anchor_x: float = float(self.cursor_x)
        self.dwell_anchor_y: float = float(self.cursor_y)
        self.dwell_start_time: float = time.perf_counter()
        self.dwell_progress: float = 0.0
        self.dwell_triggered_this_anchor: bool = False
        self.last_click_time: float = 0.0
        self.last_significant_move_time: float = time.perf_counter()
        self.total_clicks: int = 0

        # Telemetry Metrics
        self.imu_packets_count: int = 0
        self.imu_hz: float = 0.0
        self._hz_window_start: float = time.perf_counter()
        self._hz_counter: int = 0
        self.phone_connected: bool = False
        self.phone_model: str = "Waiting for Android VR Headset..."
        self.phone_vr_mode: str = "Native sustained-VR + GameRotationVector"
        self.last_imu_timestamp: float = 0.0

    def refresh_monitors(self):
        self.monitors = get_monitors_info()
        if not any(m["index"] == self.active_monitor_index for m in self.monitors):
            virtual_mon = next((m for m in self.monitors if m["is_virtual"]), None)
            if virtual_mon:
                self.active_monitor_index = virtual_mon["index"]
            elif self.monitors:
                self.active_monitor_index = self.monitors[-1]["index"]
            else:
                self.active_monitor_index = 1

    def get_active_monitor_rect(self) -> dict:
        if not self.monitors:
            self.monitors = get_monitors_info()
        for m in self.monitors:
            if m["index"] == self.active_monitor_index:
                return m
        if self.monitors:
            return self.monitors[-1]
        return {"index": 1, "left": 0, "top": 0, "width": 1920, "height": 1080, "label": "Default"}

    def set_virtual_monitor_enabled(self, enabled: bool):
        self.virtual_monitor_enabled = enabled
        if enabled:
            ok, msg = self.vdd_manager.create_third_monitor()
            self.vdd_status_msg = msg
            self.refresh_monitors()
            virtual_mon = next((m for m in self.monitors if m["is_virtual"]), None)
            if virtual_mon:
                self.active_monitor_index = virtual_mon["index"]
        else:
            ok, msg = self.vdd_manager.remove_third_monitor()
            self.vdd_status_msg = msg
            time.sleep(0.3)
            self.refresh_monitors()

    def recenter(self):
        self.center_yaw = self.raw_yaw
        self.center_pitch = self.raw_pitch
        self.center_roll = self.raw_roll
        self.has_centered = True
        self.filter_x.reset()
        self.filter_y.reset()
        rect = self.get_active_monitor_rect()
        self.dwell_anchor_x = float(rect["left"] + rect["width"] // 2)
        self.dwell_anchor_y = float(rect["top"] + rect["height"] // 2)
        self.dwell_start_time = time.perf_counter()
        self.dwell_progress = 0.0
        self.dwell_triggered_this_anchor = False

    def process_imu_packet(self, data: dict) -> dict:
        bind_input_desktop()
        now = time.perf_counter()
        self.last_imu_timestamp = now
        self.phone_connected = True
        self.imu_packets_count += 1
        self._hz_counter += 1
        elapsed_hz = now - self._hz_window_start
        if elapsed_hz >= 0.5:
            self.imu_hz = round(self._hz_counter / elapsed_hz, 1)
            self._hz_counter = 0
            self._hz_window_start = now

        yaw = float(data.get("yaw", 0.0))
        pitch = float(data.get("pitch", 0.0))
        roll = float(data.get("roll", 0.0))
        if "q" in data and isinstance(data["q"], list) and len(data["q"]) == 4:
            self.quat = [float(v) for v in data["q"]]

        if "device" in data:
            self.phone_model = str(data["device"])

        self.raw_yaw = yaw
        self.raw_pitch = pitch
        self.raw_roll = roll

        if not self.has_centered:
            self.recenter()

        dyaw = normalize_angle_deg(self.raw_yaw - self.center_yaw)
        dpitch = normalize_angle_deg(self.raw_pitch - self.center_pitch)
        droll = normalize_angle_deg(self.raw_roll - self.center_roll)

        if self.invert_yaw:
            dyaw = -dyaw
        if self.invert_pitch:
            dpitch = -dpitch

        if abs(dyaw - self.last_filtered_yaw) < self.deadzone_deg:
            dyaw = self.last_filtered_yaw
        else:
            self.last_filtered_yaw = dyaw

        if abs(dpitch - self.last_filtered_pitch) < self.deadzone_deg:
            dpitch = self.last_filtered_pitch
        else:
            self.last_filtered_pitch = dpitch

        self.rel_yaw = dyaw
        self.rel_pitch = dpitch
        self.rel_roll = droll

        raw_nx = 0.5 + (dyaw / max(5.0, self.fov_yaw_deg))
        raw_ny = 0.5 + (dpitch / max(5.0, self.fov_pitch_deg))
        raw_nx = max(0.0, min(1.0, raw_nx))
        raw_ny = max(0.0, min(1.0, raw_ny))

        self.filter_x.min_cutoff = self.smoothing_min_cutoff
        self.filter_x.beta = self.smoothing_beta
        self.filter_y.min_cutoff = self.smoothing_min_cutoff
        self.filter_y.beta = self.smoothing_beta

        self.norm_x = max(0.0, min(1.0, self.filter_x(raw_nx, now)))
        self.norm_y = max(0.0, min(1.0, self.filter_y(raw_ny, now)))

        # Map normalized coordinates [0..1] onto the active monitor (e.g. Monitor 3 Virtual Display)
        rect = self.get_active_monitor_rect()
        self.screen_left = rect["left"]
        self.screen_top = rect["top"]
        self.screen_w = rect["width"]
        self.screen_h = rect["height"]

        target_x = self.screen_left + int(self.norm_x * (self.screen_w - 1))
        target_y = self.screen_top + int(self.norm_y * (self.screen_h - 1))
        if math.hypot(target_x - self.cursor_x, target_y - self.cursor_y) > 8.0:
            self.last_significant_move_time = now
        self.cursor_x = target_x
        self.cursor_y = target_y

        if self.mouse_control_enabled:
            set_cursor_pos(target_x, target_y)

        click_fired = False
        if self.dwell_enabled and self.mouse_control_enabled:
            dist = math.hypot(target_x - self.dwell_anchor_x, target_y - self.dwell_anchor_y)
            if dist > self.dwell_radius_px:
                self.dwell_anchor_x = float(target_x)
                self.dwell_anchor_y = float(target_y)
                self.dwell_start_time = now
                self.dwell_progress = 0.0
                self.dwell_triggered_this_anchor = False
            else:
                self.dwell_anchor_x = 0.9 * self.dwell_anchor_x + 0.1 * target_x
                self.dwell_anchor_y = 0.9 * self.dwell_anchor_y + 0.1 * target_y
                if not self.dwell_triggered_this_anchor:
                    cooldown_elapsed = (now - self.last_click_time) * 1000.0
                    if cooldown_elapsed >= self.dwell_cooldown_ms:
                        elapsed_ms = (now - self.dwell_start_time) * 1000.0
                        self.dwell_progress = max(0.0, min(1.0, elapsed_ms / max(150, self.dwell_time_ms)))
                        if self.dwell_progress >= 1.0:
                            trigger_mouse_click(self.click_mode)
                            self.last_click_time = now
                            self.total_clicks += 1
                            self.dwell_triggered_this_anchor = True
                            self.dwell_progress = 1.0
                            click_fired = True
                    else:
                        self.dwell_progress = 0.0
                        self.dwell_start_time = now
                else:
                    since_click_ms = (now - self.last_click_time) * 1000.0
                    if since_click_ms > 300:
                        self.dwell_progress = 0.0
        else:
            self.dwell_progress = 0.0

        return {
            "nx": round(self.norm_x, 4),
            "ny": round(self.norm_y, 4),
            "cx": self.cursor_x,
            "cy": self.cursor_y,
            "dwell": round(self.dwell_progress, 3),
            "clicked": click_fired,
            "clickMode": self.click_mode,
            "totalClicks": self.total_clicks,
            "yaw": round(self.rel_yaw, 2),
            "pitch": round(self.rel_pitch, 2),
            "roll": round(self.rel_roll, 2),
            "hz": self.imu_hz,
            "mon": self.active_monitor_index,
        }

    def update_config(self, cfg: dict):
        if "virtual_monitor_enabled" in cfg:
            want_vdd = bool(cfg["virtual_monitor_enabled"])
            if want_vdd != self.virtual_monitor_enabled:
                self.set_virtual_monitor_enabled(want_vdd)
        if "active_monitor_index" in cfg:
            self.refresh_monitors()
            idx = int(cfg["active_monitor_index"])
            if any(m["index"] == idx for m in self.monitors):
                self.active_monitor_index = idx
        if "mouse_control_enabled" in cfg:
            self.mouse_control_enabled = bool(cfg["mouse_control_enabled"])
        if "dwell_enabled" in cfg:
            self.dwell_enabled = bool(cfg["dwell_enabled"])
        if "dwell_time_ms" in cfg:
            self.dwell_time_ms = max(200, min(3000, int(cfg["dwell_time_ms"])))
        if "dwell_radius_px" in cfg:
            self.dwell_radius_px = max(8.0, min(120.0, float(cfg["dwell_radius_px"])))
        if "click_mode" in cfg and cfg["click_mode"] in ("left", "right", "double"):
            self.click_mode = cfg["click_mode"]
        if "fov_yaw_deg" in cfg:
            self.fov_yaw_deg = max(10.0, min(90.0, float(cfg["fov_yaw_deg"])))
        if "fov_pitch_deg" in cfg:
            self.fov_pitch_deg = max(8.0, min(70.0, float(cfg["fov_pitch_deg"])))
        if "smoothing_min_cutoff" in cfg:
            self.smoothing_min_cutoff = max(0.1, min(5.0, float(cfg["smoothing_min_cutoff"])))
        if "smoothing_beta" in cfg:
            self.smoothing_beta = max(0.001, min(0.5, float(cfg["smoothing_beta"])))
        if "deadzone_deg" in cfg:
            self.deadzone_deg = max(0.0, min(2.0, float(cfg["deadzone_deg"])))
        if "invert_yaw" in cfg:
            self.invert_yaw = bool(cfg["invert_yaw"])
        if "invert_pitch" in cfg:
            self.invert_pitch = bool(cfg["invert_pitch"])
        if "stream_fps" in cfg:
            self.stream_fps = max(10, min(60, int(cfg["stream_fps"])))
        if "stream_quality" in cfg:
            self.stream_quality = max(40, min(98, int(cfg["stream_quality"])))
        if "stream_scale" in cfg:
            self.stream_scale = max(0.4, min(1.0, float(cfg["stream_scale"])))
        if "vr_screen_distance" in cfg:
            self.vr_screen_distance = max(0.75, min(4.5, float(cfg["vr_screen_distance"])))
        if "vr_screen_curvature" in cfg:
            self.vr_screen_curvature = max(0.0, min(1.0, float(cfg["vr_screen_curvature"])))
        if "vr_ipd_offset" in cfg:
            self.vr_ipd_offset = max(0.040, min(0.080, float(cfg["vr_ipd_offset"])))
        if "vr_gaze_follow" in cfg:
            self.vr_gaze_follow = bool(cfg["vr_gaze_follow"])
        if "vr_zoom_crop" in cfg:
            self.vr_zoom_crop = bool(cfg["vr_zoom_crop"])
        if "vr_zoom_factor" in cfg:
            self.vr_zoom_factor = max(1.2, min(3.5, float(cfg["vr_zoom_factor"])))

    def to_status_dict(self) -> dict:
        now = time.perf_counter()
        if self.last_imu_timestamp > 0 and (now - self.last_imu_timestamp) > 2.5:
            self.phone_connected = False
            self.imu_hz = 0.0

        return {
            "phone_connected": self.phone_connected,
            "phone_model": self.phone_model,
            "phone_vr_mode": self.phone_vr_mode,
            "imu_hz": self.imu_hz,
            "imu_packets": self.imu_packets_count,
            "raw_yaw": round(self.raw_yaw, 2),
            "raw_pitch": round(self.raw_pitch, 2),
            "raw_roll": round(self.raw_roll, 2),
            "rel_yaw": round(self.rel_yaw, 2),
            "rel_pitch": round(self.rel_pitch, 2),
            "rel_roll": round(self.rel_roll, 2),
            "quat": [round(q, 4) for q in self.quat],
            "norm_x": round(self.norm_x, 4),
            "norm_y": round(self.norm_y, 4),
            "cursor_x": self.cursor_x,
            "cursor_y": self.cursor_y,
            "screen_left": self.screen_left,
            "screen_top": self.screen_top,
            "screen_w": self.screen_w,
            "screen_h": self.screen_h,
            "dwell_progress": round(self.dwell_progress, 3),
            "total_clicks": self.total_clicks,
            "virtual_monitor_active": self.vdd_manager.active,
            "vdd_status_msg": self.vdd_status_msg,
            "active_monitor_index": self.active_monitor_index,
            "stream_clients_count": len(stream_clients),
            "monitors": self.monitors,
            "config": {
                "virtual_monitor_enabled": self.virtual_monitor_enabled,
                "active_monitor_index": self.active_monitor_index,
                "mouse_control_enabled": self.mouse_control_enabled,
                "dwell_enabled": self.dwell_enabled,
                "dwell_time_ms": self.dwell_time_ms,
                "dwell_radius_px": self.dwell_radius_px,
                "click_mode": self.click_mode,
                "fov_yaw_deg": self.fov_yaw_deg,
                "fov_pitch_deg": self.fov_pitch_deg,
                "smoothing_min_cutoff": self.smoothing_min_cutoff,
                "smoothing_beta": self.smoothing_beta,
                "deadzone_deg": self.deadzone_deg,
                "invert_yaw": self.invert_yaw,
                "invert_pitch": self.invert_pitch,
                "stream_fps": self.stream_fps,
                "stream_quality": self.stream_quality,
                "stream_scale": self.stream_scale,
                "vr_screen_distance": self.vr_screen_distance,
                "vr_screen_curvature": self.vr_screen_curvature,
                "vr_ipd_offset": self.vr_ipd_offset,
                "vr_gaze_follow": self.vr_gaze_follow,
                "vr_zoom_crop": self.vr_zoom_crop,
                "vr_zoom_factor": self.vr_zoom_factor,
            },
        }


engine = AetherEngineState()


@atexit.register
def _cleanup_virtual_monitor():
    try:
        engine.vdd_manager.remove_third_monitor()
    except Exception:
        pass


# ==============================================================================
# FASTAPI APPLICATION & REAL-TIME WEBSOCKET CHANNELS
# ==============================================================================

app = FastAPI(title="AETHER-VR // Spatial Head-Tracking & 3rd Virtual Monitor Engine")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

BASE_DIR = Path(__file__).parent
STATIC_DIR = BASE_DIR / "static"
STATIC_DIR.mkdir(exist_ok=True)

dashboard_clients: Set[WebSocket] = set()
vr_display_clients: Set[WebSocket] = set()
stream_clients: Set[WebSocket] = set()


def get_local_ips() -> List[str]:
    ips = []
    try:
        for iface, snics in psutil.net_if_addrs().items():
            for snic in snics:
                if snic.family == socket.AF_INET and not snic.address.startswith("127."):
                    ips.append(snic.address)
    except Exception:
        pass
    if not ips:
        ips.append("127.0.0.1")
    return ips


@app.get("/api/snapshot")
async def api_snapshot():
    from fastapi.responses import Response

    mon_rect = engine.get_active_monitor_rect()
    frame_bytes = await asyncio.to_thread(
        capture_frame_bytes,
        mon_rect,
        engine.stream_scale,
        engine.stream_quality,
        engine.norm_x,
        engine.norm_y,
        engine.dwell_progress,
        engine.vr_zoom_crop,
        engine.vr_zoom_factor,
    )
    return Response(content=frame_bytes, media_type="image/jpeg")


@app.get("/api/status")
async def api_status():
    engine.refresh_monitors()
    status = engine.to_status_dict()
    status["local_ips"] = get_local_ips()
    status["cpu_percent"] = psutil.cpu_percent(interval=None)
    status["ram_percent"] = psutil.virtual_memory().percent
    return JSONResponse(status)


@app.post("/api/recenter")
async def api_recenter():
    engine.recenter()
    return JSONResponse({"ok": True, "status": engine.to_status_dict()})


@app.post("/api/config")
async def api_update_config(payload: dict):
    engine.update_config(payload)
    return JSONResponse({"ok": True, "status": engine.to_status_dict()})


@app.post("/api/move_window_to_vr")
async def api_move_window_to_vr():
    """Moves a test window or cursor focus onto the 3rd Virtual Monitor."""
    rect = engine.get_active_monitor_rect()
    set_cursor_pos(rect["left"] + rect["width"] // 2, rect["top"] + rect["height"] // 2)
    return JSONResponse({"ok": True, "monitor": rect})


@app.post("/api/click")
async def api_manual_click(payload: Optional[dict] = None):
    btn = (payload or {}).get("button", engine.click_mode)
    trigger_mouse_click(btn)
    engine.total_clicks += 1
    engine.last_click_time = time.perf_counter()
    return JSONResponse({"ok": True, "total_clicks": engine.total_clicks})


@app.websocket("/ws/vr")
async def ws_vr_headset(websocket: WebSocket):
    await websocket.accept()
    vr_display_clients.add(websocket)
    try:
        while True:
            msg = await websocket.receive_text()
            data = json.loads(msg)
            msg_type = data.get("type", "imu")

            if msg_type == "imu":
                feedback = engine.process_imu_packet(data)
                feedback["type"] = "telemetry"
                feedback["dist"] = engine.vr_screen_distance
                feedback["curv"] = engine.vr_screen_curvature
                feedback["ipd"] = engine.vr_ipd_offset
                feedback["aspect"] = round(engine.screen_w / max(1, engine.screen_h), 3)
                feedback["follow"] = engine.vr_gaze_follow
                await websocket.send_text(json.dumps(feedback))
                if len(stream_clients) == 0 and (engine.imu_packets_count % 60 == 1):
                    await websocket.send_text(json.dumps({"type": "reconnect_stream"}))
            elif msg_type == "recenter":
                engine.recenter()
                await websocket.send_text(json.dumps({"type": "event", "event": "recentered"}))
            elif msg_type == "click":
                btn = data.get("button", engine.click_mode)
                trigger_mouse_click(btn)
                engine.total_clicks += 1
                engine.last_click_time = time.perf_counter()
                await websocket.send_text(json.dumps({"type": "event", "event": "clicked", "button": btn}))
            elif msg_type == "toggle_mode":
                modes = ["left", "right", "double"]
                idx = (modes.index(engine.click_mode) + 1) % len(modes)
                engine.click_mode = modes[idx]
                await websocket.send_text(json.dumps({"type": "event", "event": "mode_changed", "mode": engine.click_mode}))
            elif msg_type == "toggle_zoom":
                engine.vr_zoom_crop = not engine.vr_zoom_crop
                await websocket.send_text(json.dumps({"type": "event", "event": "zoom_toggled", "zoom": engine.vr_zoom_crop}))
            elif msg_type == "toggle_dwell":
                engine.dwell_enabled = not engine.dwell_enabled
                engine.dwell_progress = 0.0
                await websocket.send_text(json.dumps({"type": "event", "event": "dwell_toggled", "dwell_enabled": engine.dwell_enabled}))
            elif msg_type == "toggle_mouse":
                engine.mouse_control_enabled = not engine.mouse_control_enabled
                engine.dwell_progress = 0.0
                await websocket.send_text(json.dumps({"type": "event", "event": "mouse_toggled", "mouse_enabled": engine.mouse_control_enabled}))
    except WebSocketDisconnect:
        pass
    except Exception:
        pass
    finally:
        vr_display_clients.discard(websocket)


def capture_frame_bytes(
    mon_rect: dict,
    scale: float,
    quality: int,
    norm_x: float,
    norm_y: float,
    dwell: float,
    zoom_crop: bool = False,
    zoom_factor: float = 1.85,
) -> bytes:
    bind_input_desktop()
    mon_idx = int(mon_rect.get("index", 2))
    with mss.MSS() as sct:
        if 1 <= mon_idx < len(sct.monitors):
            monitor = sct.monitors[mon_idx]
        else:
            monitor = sct.monitors[-1]
        raw_shot = sct.grab(monitor)
        img = Image.frombytes("RGB", raw_shot.size, raw_shot.bgra, "raw", "BGRX")

    src_w, src_h = raw_shot.width, raw_shot.height

    # Only draw the spatial cursor reticle when Mouse Control is active AND either Dwell is enabled
    # OR the head moved within the last 2.5 seconds (Cinema Auto-Hide so your movie view is 100% clean!)
    show_cursor_overlay = engine.mouse_control_enabled and (
        engine.dwell_enabled or (time.perf_counter() - getattr(engine, "last_significant_move_time", 0.0) < 2.5)
    )
    if show_cursor_overlay:
        draw = ImageDraw.Draw(img)
        cx = int(norm_x * src_w)
        cy = int(norm_y * src_h)
        r_outer = 14
        draw.ellipse((cx - r_outer, cy - r_outer, cx + r_outer, cy + r_outer), outline=(0, 240, 255), width=2)
        draw.ellipse((cx - 3, cy - 3, cx + 3, cy + 3), fill=(255, 255, 255))
        if dwell > 0.02:
            extent = int(360 * dwell)
            r_dwell = 23
            draw.arc(
                (cx - r_dwell, cy - r_dwell, cx + r_dwell, cy + r_dwell),
                start=-90,
                end=-90 + extent,
                fill=(0, 255, 163),
                width=4,
            )
    else:
        cx = int(norm_x * src_w)
        cy = int(norm_y * src_h)

    # Optional Gaze-Centered Retina Magnifier Crop (Delivers 1:1 pixel-perfect clarity around where you look)
    if zoom_crop and zoom_factor > 1.05:
        crop_w = int(src_w / zoom_factor)
        crop_h = int(src_h / zoom_factor)
        left = max(0, min(src_w - crop_w, cx - crop_w // 2))
        top = max(0, min(src_h - crop_h, cy - crop_h // 2))
        img = img.crop((left, top, left + crop_w, top + crop_h))
    elif scale < 0.99:
        target_w = int(src_w * scale)
        target_h = int(src_h * scale)
        if target_w > 0 and target_h > 0:
            img = img.resize((target_w, target_h), Image.Resampling.LANCZOS)

    buf = io.BytesIO()
    # subsampling=0 enables 4:4:4 full-color chroma so colored text & UI edges have zero compression blur!
    img.save(buf, format="JPEG", quality=quality, subsampling=0, optimize=False)
    return buf.getvalue()


@app.websocket("/ws/stream")
async def ws_desktop_stream(websocket: WebSocket):
    """
    Streams the selected monitor (defaults to Monitor 3: Virtual 3rd Display `1920x1080`)
    to the Android VR Headset's curved IMAX OpenGL surface.
    """
    await websocket.accept()
    stream_clients.add(websocket)
    try:
        while True:
            t0 = time.perf_counter()
            mon_rect = engine.get_active_monitor_rect()
            frame_bytes = await asyncio.to_thread(
                capture_frame_bytes,
                mon_rect,
                engine.stream_scale,
                engine.stream_quality,
                engine.norm_x,
                engine.norm_y,
                engine.dwell_progress,
            )
            await websocket.send_bytes(frame_bytes)

            elapsed = time.perf_counter() - t0
            sleep_dur = max(0.010, (1.0 / max(10, engine.stream_fps)) - elapsed)
            await asyncio.sleep(sleep_dur)
    except WebSocketDisconnect:
        pass
    except Exception:
        pass
    finally:
        stream_clients.discard(websocket)


@app.websocket("/ws/dashboard")
async def ws_dashboard(websocket: WebSocket):
    await websocket.accept()
    dashboard_clients.add(websocket)
    tick = 0
    try:
        while True:
            tick += 1
            if tick % 30 == 0:
                engine.refresh_monitors()
            status = engine.to_status_dict()
            status["local_ips"] = get_local_ips()
            status["cpu_percent"] = psutil.cpu_percent(interval=None)
            status["ram_percent"] = psutil.virtual_memory().percent
            await websocket.send_text(json.dumps(status))
            await asyncio.sleep(0.033)
    except WebSocketDisconnect:
        pass
    except Exception:
        pass
    finally:
        dashboard_clients.discard(websocket)


@app.get("/download/apk")
async def download_latest_apk():
    apk_path = BASE_DIR.parent / "android-app" / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    return FileResponse(apk_path, filename="AetherVR-HighRes.apk", media_type="application/vnd.android.package-archive")


@app.get("/")
async def index_page():
    return FileResponse(STATIC_DIR / "index.html")


@app.get("/vr-web")
async def vr_web_fallback():
    return FileResponse(STATIC_DIR / "vr_web.html")


app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8780, log_level="warning")
