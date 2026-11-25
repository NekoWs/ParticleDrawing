"""Launch a process tree on a Windows desktop that is never made active."""

import ctypes as ct
from contextlib import contextmanager
from ctypes import wintypes as wt
import msvcrt
import os
import uuid


class StartupInfo(ct.Structure):
    _fields_ = [
        ("cb", wt.DWORD), ("reserved", wt.LPWSTR), ("desktop", wt.LPWSTR), ("title", wt.LPWSTR),
        ("x", wt.DWORD), ("y", wt.DWORD), ("width", wt.DWORD), ("height", wt.DWORD),
        ("columns", wt.DWORD), ("rows", wt.DWORD), ("fill", wt.DWORD), ("flags", wt.DWORD),
        ("show", wt.WORD), ("reserved_size", wt.WORD), ("reserved_bytes", ct.c_void_p),
        ("stdin", wt.HANDLE), ("stdout", wt.HANDLE), ("stderr", wt.HANDLE),
    ]


class ProcessInfo(ct.Structure):
    _fields_ = [
        ("process", wt.HANDLE), ("thread", wt.HANDLE), ("pid", wt.DWORD), ("tid", wt.DWORD),
    ]


def bind(library, name, result, *arguments):
    function = getattr(library, name)
    function.restype = result
    function.argtypes = arguments
    return function


kernel = ct.WinDLL("kernel32", use_last_error=True)
user = ct.WinDLL("user32", use_last_error=True)
create_desktop = bind(user, "CreateDesktopW", wt.HANDLE, wt.LPCWSTR, wt.LPCWSTR, ct.c_void_p,
                      wt.DWORD, wt.DWORD, ct.c_void_p)
close_desktop = bind(user, "CloseDesktop", wt.BOOL, wt.HANDLE)
create_job = bind(kernel, "CreateJobObjectW", wt.HANDLE, ct.c_void_p, wt.LPCWSTR)
create_process = bind(kernel, "CreateProcessW", wt.BOOL, wt.LPCWSTR, wt.LPWSTR,
                      ct.c_void_p, ct.c_void_p, wt.BOOL, wt.DWORD, ct.c_void_p, wt.LPCWSTR,
                      ct.POINTER(StartupInfo), ct.POINTER(ProcessInfo))
assign_job = bind(kernel, "AssignProcessToJobObject", wt.BOOL, wt.HANDLE, wt.HANDLE)
terminate_job = bind(kernel, "TerminateJobObject", wt.BOOL, wt.HANDLE, wt.UINT)
terminate_process = bind(kernel, "TerminateProcess", wt.BOOL, wt.HANDLE, wt.UINT)
resume = bind(kernel, "ResumeThread", wt.DWORD, wt.HANDLE)
wait = bind(kernel, "WaitForSingleObject", wt.DWORD, wt.HANDLE, wt.DWORD)
get_exit_code = bind(kernel, "GetExitCodeProcess", wt.BOOL, wt.HANDLE, ct.POINTER(wt.DWORD))
close = bind(kernel, "CloseHandle", wt.BOOL, wt.HANDLE)

CREATE_SUSPENDED = 0x4
CREATE_UNICODE_ENVIRONMENT = 0x400
CREATE_NO_WINDOW = 0x08000000
STARTF_USESTDHANDLES = 0x100
DESKTOP_ACCESS = 0x01FF


def checked(value):
    if not value:
        raise ct.WinError(ct.get_last_error())
    return value


class Process:
    def __init__(self, handle):
        self.handle = handle

    def poll(self):
        wait_result = wait(self.handle, 0)
        if wait_result == 258:
            return None
        if wait_result != 0:
            raise ct.WinError(ct.get_last_error())
        status = wt.DWORD()
        checked(get_exit_code(self.handle, ct.byref(status)))
        return status.value


@contextmanager
def launch(executable, commandline, cwd, environment, log):
    desktop_name = f"PDRecording-{uuid.uuid4().hex}"
    desktop = checked(create_desktop(desktop_name, None, None, 0, DESKTOP_ACCESS, None))
    process = ProcessInfo()
    job = None
    assigned = False
    try:
        job = checked(create_job(None, None))
        startup = StartupInfo()
        startup.cb = ct.sizeof(startup)
        startup.desktop = desktop_name
        startup.flags = STARTF_USESTDHANDLES
        env = ct.create_unicode_buffer("\0".join(
            f"{key}={value}" for key, value in sorted(environment.items(), key=lambda item: item[0].upper())
        ) + "\0")
        with open(os.devnull, "rb") as input_stream, log.open("wb") as output_stream:
            startup.stdin = msvcrt.get_osfhandle(input_stream.fileno())
            startup.stdout = startup.stderr = msvcrt.get_osfhandle(output_stream.fileno())
            os.set_handle_inheritable(startup.stdin, True)
            os.set_handle_inheritable(startup.stdout, True)
            checked(create_process(executable, ct.create_unicode_buffer(commandline), None, None, True,
                                   CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT | CREATE_NO_WINDOW,
                                   env, str(cwd), ct.byref(startup), ct.byref(process)))
        checked(assign_job(job, process.process))
        assigned = True
        if resume(process.thread) == 0xFFFFFFFF:
            raise ct.WinError(ct.get_last_error())
        yield Process(process.process)
    finally:
        if assigned:
            terminate_job(job, 1)
        elif process.process:
            terminate_process(process.process, 1)
        if process.process:
            wait(process.process, 10000)
            close(process.process)
            close(process.thread)
        if job:
            close(job)
        close_desktop(desktop)
